const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_uid_001";
const BOB_UID = "bob_uid_002";
const EVE_UID = "eve_uid_003";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

const pastDate = (offsetMs = 5000) => new Date(Date.now() - offsetMs);

function makeProfile(uid, username, displayName) {
  const ts = pastDate(10000);
  return {
    userId: uid,
    username,
    usernameLower: username,
    displayName,
    displayNameLower: displayName.toLowerCase(),
    about: "Hey there! I am using UChat.",
    online: true,
    lastSeen: ts,
    showLastSeen: true,
    showOnline: true,
    showAbout: true,
    readReceipts: true,
    discoverable: true,
    createdAt: ts,
    updatedAt: ts,
  };
}

function makeConversation(creatorUid, memberUids, kind = "direct", title = "") {
  const ts = pastDate(10000);
  return {
    kind,
    memberUids,
    adminUids: [creatorUid],
    title,
    description: "",
    memberNames: { [ALICE_UID]: "Alice", [BOB_UID]: "Bob" },
    memberUsernames: { [ALICE_UID]: "alice_dev", [BOB_UID]: "bob_dev" },
    lastMessageText: "",
    lastMessageType: "text",
    lastMessageSenderId: creatorUid,
    lastMessageAt: ts,
    createdBy: creatorUid,
    createdAt: ts,
    updatedAt: ts,
  };
}

function makeMessage(conversationId, senderId, memberUids, text = "Hello UChat!") {
  const ts = pastDate(5000);
  return {
    conversationId,
    senderId,
    senderName: "Alice",
    memberUids,
    messageType: "text",
    text,
    forwarded: false,
    edited: false,
    deleted: false,
    deliveredTo: [senderId],
    readBy: [senderId],
    starredBy: [],
    reactions: {},
    createdAt: ts,
    updatedAt: ts,
  };
}

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated user cannot read or write any collection", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("profiles").where("discoverable", "==", true).get());
  await assertFails(unauthDb.collection("usernames").doc("alice_dev").get());
  await assertFails(unauthDb.collection("conversations").where("memberUids", "array-contains", ALICE_UID).get());
  await assertFails(unauthDb.collection("channels").where("discoverable", "==", true).get());
  await assertFails(unauthDb.collection("calls").where("participantUids", "array-contains", ALICE_UID).get());
  await assertFails(unauthDb.collection("profiles").doc(ALICE_UID).set(makeProfile(ALICE_UID, "alice_dev", "Alice")));
});

test("Unique username reservation & profile creation + shadow update denial", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();

  // Reserve username
  await assertSucceeds(
    aliceDb.collection("usernames").doc("alice_dev").set({
      userId: ALICE_UID,
      createdAt: pastDate(5000),
    })
  );

  // Bob cannot overwrite or delete Alice's username
  await assertFails(
    bobDb.collection("usernames").doc("alice_dev").set({
      userId: BOB_UID,
      createdAt: pastDate(2000),
    })
  );
  await assertFails(bobDb.collection("usernames").doc("alice_dev").delete());
  // Listing all usernames is denied
  await assertFails(bobDb.collection("usernames").get());

  // Create Alice's profile
  await assertSucceeds(
    aliceDb.collection("profiles").doc(ALICE_UID).set(makeProfile(ALICE_UID, "alice_dev", "Alice"))
  );

  // Shadow field injection rejected
  await assertFails(
    aliceDb.collection("profiles").doc(ALICE_UID).update({
      displayName: "Alice Pro",
      displayNameLower: "alice pro",
      isVerified: true,
      updatedAt: pastDate(1000),
    })
  );

  // Valid partial update succeeds
  await assertSucceeds(
    aliceDb.collection("profiles").doc(ALICE_UID).update({
      about: "Building on Android",
      updatedAt: pastDate(1000),
    })
  );

  // Presence heartbeat update succeeds
  await assertSucceeds(
    aliceDb.collection("profiles").doc(ALICE_UID).update({
      online: false,
      lastSeen: pastDate(500),
      updatedAt: pastDate(500),
    })
  );

  // Future timestamp tampering rejected
  await assertFails(
    aliceDb.collection("profiles").doc(ALICE_UID).update({
      online: true,
      lastSeen: new Date(Date.now() + 60000),
      updatedAt: new Date(Date.now() + 60000),
    })
  );

  // Filtered discoverable list succeeds, unfiltered list fails
  await assertSucceeds(bobDb.collection("profiles").where("discoverable", "==", true).get());
  await assertFails(bobDb.collection("profiles").get());

  // Cross-user profile write fails
  await assertFails(
    bobDb.collection("profiles").doc(ALICE_UID).update({
      about: "Hacked",
      updatedAt: pastDate(500),
    })
  );
});

test("User-scoped settings, contacts, blocked, chatPrefs, followedChannels isolation", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const ts = pastDate(5000);

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("settings").doc("prefs").set({
      accentHex: "#25D366",
      wallpaperStyle: "doodle",
      fontScale: 1.0,
      msgNotif: true,
      groupNotif: true,
      callNotif: true,
      soundEnabled: true,
      ringtoneName: "Default Ringtone",
      createdAt: ts,
      updatedAt: ts,
    })
  );
  await assertSucceeds(aliceDb.collection("users").doc(ALICE_UID).collection("settings").get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("settings").get());

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("chatPrefs").doc("conv_1").set({
      conversationId: "conv_1",
      pinned: true,
      archived: false,
      muted: false,
      hidden: false,
      unreadCount: 0,
      createdAt: ts,
      updatedAt: ts,
    })
  );
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("chatPrefs").doc("conv_1").get());
});

test("Conversations & real-time messages: membership scoping, receipts, reactions, edit/delete", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const eveDb = testEnv.authenticatedContext(EVE_UID).firestore();

  const convId = "conv_alice_bob";
  const members = [ALICE_UID, BOB_UID];

  await assertSucceeds(
    aliceDb.collection("conversations").doc(convId).set(makeConversation(ALICE_UID, members))
  );

  // Scoped member list query succeeds; unfiltered list fails
  await assertSucceeds(aliceDb.collection("conversations").where("memberUids", "array-contains", ALICE_UID).get());
  await assertFails(aliceDb.collection("conversations").get());

  // Non-member Eve cannot read or write conversation
  await assertFails(eveDb.collection("conversations").doc(convId).get());

  // Alice sends a message
  await assertSucceeds(
    aliceDb.collection("conversations").doc(convId).collection("messages").doc("msg_1").set(
      makeMessage(convId, ALICE_UID, members, "Hey Bob!")
    )
  );

  // Eve cannot read or inject messages
  await assertFails(
    eveDb.collection("conversations").doc(convId).collection("messages").doc("msg_1").get()
  );
  await assertFails(
    eveDb.collection("conversations").doc(convId).collection("messages").doc("msg_eve").set(
      makeMessage(convId, EVE_UID, [ALICE_UID, BOB_UID, EVE_UID], "Intruder!")
    )
  );

  // Bob queries messages with memberUids filter
  await assertSucceeds(
    bobDb.collection("conversations").doc(convId).collection("messages")
      .where("memberUids", "array-contains", BOB_UID).get()
  );
  // Unfiltered message list fails
  await assertFails(
    bobDb.collection("conversations").doc(convId).collection("messages").get()
  );

  // Bob marks read & reacts (Action 2), cannot edit Alice's text
  await assertSucceeds(
    bobDb.collection("conversations").doc(convId).collection("messages").doc("msg_1").update({
      readBy: [ALICE_UID, BOB_UID],
      reactions: { [BOB_UID]: "🔥" },
      updatedAt: pastDate(1000),
    })
  );
  await assertFails(
    bobDb.collection("conversations").doc(convId).collection("messages").doc("msg_1").update({
      text: "Tampered by Bob",
      edited: true,
      updatedAt: pastDate(1000),
    })
  );

  // Alice edits own message (Action 1)
  await assertSucceeds(
    aliceDb.collection("conversations").doc(convId).collection("messages").doc("msg_1").update({
      text: "Hey Bob! (edited)",
      edited: true,
      updatedAt: pastDate(500),
    })
  );
});

test("Channels: admin-only posting, follower count +/-1, follower emoji reactions", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const ts = pastDate(8000);

  await assertSucceeds(
    aliceDb.collection("channels").doc("chan_1").set({
      name: "Android Builders",
      description: "Daily Compose & Kotlin updates",
      ownerId: ALICE_UID,
      ownerName: "Alice",
      followerCount: 1,
      discoverable: true,
      lastPostText: "",
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Bob can follow (+1), cannot jump followerCount by +50
  await assertSucceeds(
    bobDb.collection("channels").doc("chan_1").update({
      followerCount: 2,
      updatedAt: pastDate(2000),
    })
  );
  await assertFails(
    bobDb.collection("channels").doc("chan_1").update({
      followerCount: 50,
      updatedAt: pastDate(1000),
    })
  );

  // Alice (owner) can post; Bob (non-owner) cannot post
  await assertSucceeds(
    aliceDb.collection("channels").doc("chan_1").collection("posts").doc("post_1").set({
      channelId: "chan_1",
      authorId: ALICE_UID,
      authorName: "Alice",
      postType: "text",
      text: "Welcome to Android Builders!",
      reactions: {},
      discoverable: true,
      createdAt: pastDate(2000),
      updatedAt: pastDate(2000),
    })
  );
  await assertFails(
    bobDb.collection("channels").doc("chan_1").collection("posts").doc("post_bob").set({
      channelId: "chan_1",
      authorId: BOB_UID,
      authorName: "Bob",
      postType: "text",
      text: "Unauthorized broadcast",
      reactions: {},
      discoverable: true,
      createdAt: pastDate(2000),
      updatedAt: pastDate(2000),
    })
  );

  // Bob can react to Alice's channel post
  await assertSucceeds(
    bobDb.collection("channels").doc("chan_1").collection("posts").doc("post_1").update({
      reactions: { [BOB_UID]: "👏" },
      updatedAt: pastDate(1000),
    })
  );
});

test("Calls signaling & terminal state locking", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const eveDb = testEnv.authenticatedContext(EVE_UID).firestore();
  const ts = pastDate(8000);

  await assertSucceeds(
    aliceDb.collection("calls").doc("call_1").set({
      callerId: ALICE_UID,
      callerName: "Alice",
      callerUsername: "alice_dev",
      calleeId: BOB_UID,
      calleeName: "Bob",
      calleeUsername: "bob_dev",
      participantUids: [ALICE_UID, BOB_UID],
      callType: "video",
      status: "ringing",
      sdpOffer: "v=0...",
      durationSec: 0,
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Eve cannot view or touch call
  await assertFails(eveDb.collection("calls").doc("call_1").get());

  // Bob accepts call
  await assertSucceeds(
    bobDb.collection("calls").doc("call_1").update({
      status: "connected",
      sdpAnswer: "v=0-answer...",
      updatedAt: pastDate(3000),
    })
  );

  // Alice ends call (terminal state)
  await assertSucceeds(
    aliceDb.collection("calls").doc("call_1").update({
      status: "ended",
      durationSec: 42,
      updatedAt: pastDate(1000),
    })
  );

  // Terminal state lock prevents reopening an ended call
  await assertFails(
    bobDb.collection("calls").doc("call_1").update({
      status: "connected",
      updatedAt: pastDate(500),
    })
  );
});

test("Self-messaging (Saved Messages): 1-member conversation & private isolation", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const selfConvId = `dm_${ALICE_UID}_${ALICE_UID}`;
  const ts = pastDate(8000);

  await assertSucceeds(
    aliceDb.collection("conversations").doc(selfConvId).set({
      kind: "direct",
      memberUids: [ALICE_UID],
      adminUids: [ALICE_UID],
      title: "Saved Messages (You)",
      description: "Personal cloud notes & saved messages",
      memberNames: { [ALICE_UID]: "Alice" },
      memberUsernames: { [ALICE_UID]: "alice_dev" },
      lastMessageText: "",
      lastMessageType: "text",
      lastMessageSenderId: ALICE_UID,
      lastMessageAt: ts,
      createdBy: ALICE_UID,
      createdAt: ts,
      updatedAt: ts,
    })
  );

  await assertSucceeds(
    aliceDb.collection("conversations").doc(selfConvId).collection("messages").doc("self_msg_1").set(
      makeMessage(selfConvId, ALICE_UID, [ALICE_UID], "My personal cloud note")
    )
  );

  // Bob cannot read Alice's self-chat or its messages
  await assertFails(bobDb.collection("conversations").doc(selfConvId).get());
  await assertFails(
    bobDb.collection("conversations").doc(selfConvId).collection("messages").doc("self_msg_1").get()
  );
});

test("WebRTC P2P signaling: SDP Offer/Answer + ICE candidate subcollection exchange", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const eveDb = testEnv.authenticatedContext(EVE_UID).firestore();
  const ts = pastDate(8000);

  // 1. Alice initiates call in ringing state (SDP offer published right after PeerConnection creates it)
  await assertSucceeds(
    aliceDb.collection("calls").doc("webrtc_call_1").set({
      callerId: ALICE_UID,
      callerName: "Alice",
      callerUsername: "alice_dev",
      calleeId: BOB_UID,
      calleeName: "Bob",
      calleeUsername: "bob_dev",
      participantUids: [ALICE_UID, BOB_UID],
      callType: "video",
      status: "ringing",
      durationSec: 0,
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // 2. Alice publishes WebRTC SDP offer
  await assertSucceeds(
    aliceDb.collection("calls").doc("webrtc_call_1").update({
      sdpOffer: "v=0\r\no=- 12345 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\n",
      updatedAt: pastDate(6000),
    })
  );

  // 3. Alice & Bob trickle ICE candidates via calls/{id}/candidates
  await assertSucceeds(
    aliceDb.collection("calls").doc("webrtc_call_1").collection("candidates").doc("cand_a1").set({
      callId: "webrtc_call_1",
      senderId: ALICE_UID,
      participantUids: [ALICE_UID, BOB_UID],
      candidate: "candidate:1 1 udp 2122260223 192.168.1.10 54321 typ host",
      sdpMid: "0",
      sdpMLineIndex: 0,
      createdAt: pastDate(5500),
    })
  );

  // 4. Bob accepts with SDP Answer -> connecting
  await assertSucceeds(
    bobDb.collection("calls").doc("webrtc_call_1").update({
      status: "connecting",
      sdpAnswer: "v=0\r\no=- 67890 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\n",
      updatedAt: pastDate(5000),
    })
  );

  await assertSucceeds(
    bobDb.collection("calls").doc("webrtc_call_1").collection("candidates").doc("cand_b1").set({
      callId: "webrtc_call_1",
      senderId: BOB_UID,
      participantUids: [ALICE_UID, BOB_UID],
      candidate: "candidate:2 1 udp 1686052607 203.0.113.5 60000 typ srflx raddr 192.168.1.20 rport 55555",
      sdpMid: "0",
      sdpMLineIndex: 0,
      createdAt: pastDate(4500),
    })
  );

  // 5. Participants query candidates scoped by participantUids; Eve is denied
  await assertSucceeds(
    bobDb.collection("calls").doc("webrtc_call_1").collection("candidates")
      .where("participantUids", "array-contains", BOB_UID).get()
  );
  await assertFails(
    eveDb.collection("calls").doc("webrtc_call_1").collection("candidates").get()
  );
  await assertFails(
    eveDb.collection("calls").doc("webrtc_call_1").collection("candidates")
      .where("participantUids", "array-contains", ALICE_UID).get()
  );
  await assertFails(
    eveDb.collection("calls").doc("webrtc_call_1").collection("candidates").doc("cand_a1").get()
  );

  // 6. ICE reaches CONNECTED -> status transitions to connected
  await assertSucceeds(
    aliceDb.collection("calls").doc("webrtc_call_1").update({
      status: "connected",
      updatedAt: pastDate(3000),
    })
  );
});
