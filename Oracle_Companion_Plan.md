# Oracle Companion — Product and Engineering Plan

13 November 2026 · Android companion for Oracle on Windows

## 1. Product scope

Oracle on Windows owns inference, conversation history, approved desktop actions and integrations. Android supplies the portable interface, microphone, camera and location. The first release works on the same local network with the PC awake and Oracle running. A later remote release is a separate deployment decision.

The first acceptance scenario is to pair a phone using a QR displayed on Windows, see the correct PC and Oracle status, open a conversation started on Windows and send a reply, then confirm **Open VS Code** and see the verified outcome on Android. Repeat after a disconnect and a desktop restart.

Primary audience is people who use a Windows PC and Android phone and want to continue a task or run a bounded action away from the desk. Success means they can identify their target PC, recover from a failed connection, and understand whether an action actually happened.

## 2. Desktop source review

Reviewed `C:\D\Projects\AI\Oracle` as source. This is a static assessment. Running services, installed models, physical devices, and network behavior were not verified. The companion directory had no visible app files at inspection.

| Area | Observed source | Reuse and gap |
|---|---|---|
| Gateway | backend/app/main.py exposes health, capabilities, chat, telemetry and action endpoints. Desktop docs describe local browser sessions and bearer auth. | Reuse services internally. Add a narrow, versioned companion boundary with per-device permissions. Existing maintenance bearer access can bypass some identity checks and must stay local. |
| Chat | ChatInput accepts bounded caller history and the endpoint supports streaming. docs/FRONTEND_BACKEND_INTEGRATION.md describes page-local current conversation. | Reuse inference and streaming. Add persistent threads and update Windows and Android to use them. Establish storage behavior from source before migration. |
| Actions | main.py and desktop.py provide proposal, confirmation and cancellation. | Reuse the launcher. Bind proposals to phone, PC and approved action. Verify what launch success actually means before labeling it in the UI. |
| Voice and Vision | Voice transcribe/speak and vision, product, OCR and QR routes are present. | Reuse existing providers. Add mobile capture, bounded uploads and clear unavailable or uncertain results. |
| Atlas | geo.py and docs/MAPS_AND_LOCATION_INTELLIGENCE.md cover place search, routes, saved places and parking. Some navigation state is browser-local. | Reuse routing and geocoding. Add a versioned handoff record, explicit phone location sharing and provider-aware route states. |
| Records and Google | Notes, tasks, memories and bounded Google routes are wired in. Desktop docs describe alerts while the app is open. | Reuse records where permitted. Add durable events for phone delivery and design Google authorization as a desktop-owned integration. |
| Protocols and Pet | Windows source contains protocol UI and an admin/lockdown path. No mobile-facing Pet contract was verified. | Start with read-only status and explicit allowlists. Keep administrator enrollment and lockdown outside the companion API. Confirm Pet lifecycle integration before scheduling it. |

## 3. Release sequence

| Milestone | Build and dependency | Exit check |
|---|---|---|
| 0 · Development setup | Confirm JDK, Android SDK, Gradle wrapper, emulator and physical phone. Establish a writable desktop checkout or approved desktop change workflow. Capture gateway behavior and audit where chat and action execution occur. | Android skeleton builds. A test Windows instance and fixtures can be run without using personal data. |
| 1 · Connection | Kotlin/Compose shell, versioned companion API, QR enrollment, encrypted connection, device registry, revoke, connection state and reconnect. Begin with one PC per app profile and explicit pairing. | Physical phone pairs over Wi-Fi. Expired, reused and invalid codes fail. Revoked phones lose live and subsequent access. |
| 2 · Shared chat and first action | Durable desktop threads, Android and Windows updates, streaming replies, reconnect, approved VS Code command and activity entry. | Both devices show the same thread after restart. Duplicate sends stay single. The phone distinguishes success, failure and unknown outcome. This completes the first usable release. |
| 3 · Voice, device health and activity | Push-to-talk with an editable transcript, streamed or completed text with optional speech, CPU/RAM where available, reminders and foreground activity. | Recording stops on cancel or backgrounding. Missing metrics show unavailable. Reconnection recovers missed durable events. |
| 4 · Vision and Atlas | One-shot photo, product/OCR results and follow-up. Foreground phone GPS, place search, route preview and desktop-to-phone trip handoff. | Camera and location are opt-in. Test a local product with uncertain identification and a Sri Lankan route with stale GPS and provider outage. |
| 5 · Desktop workflows | Approved media and app controls, file exchange, safe protocol execution, Pet status, optional notes and tasks. | Each feature has a permission boundary, visible progress and an observable outcome. File transfers can recover from interruption. |
| 6 · Remote access and distribution | Evaluate private VPN first, or build an opt-in relay if reach and delivery warrant it. Add background notifications, signed releases, update/rollback and diagnostics. | Test mobile data, suspended clients, revocation, update compatibility and alert delivery. Define what remains available while Windows sleeps. |

## 4. Architecture and data contracts

**Android** uses Kotlin, Jetpack Compose, a lifecycle-aware state model, Android Keystore-backed protection for credentials, and local storage for permitted cache and drafts. Choose stable build dependencies during milestone 0. The UI can run against test fixtures before the desktop boundary is ready. Camera, microphone and location permissions are requested when the user invokes the corresponding feature.

**Windows** keeps the existing desktop API local. An opt-in companion listener exposes only versioned operations and calls explicit adapters for Oracle services. Use TLS with the desktop identity authenticated through the locally presented enrollment QR. Show PC name and the intended access on Windows and Android. Scope and expire enrollment secrets, redeem them atomically, and support device revocation and key rotation. LAN reachability alone grants no access. Keep Ollama and database listeners local. Surface setup errors for firewall, wrong network and unavailable Oracle.

**Proposed companion operations** are pair, check status/capabilities, list and read threads, send/cancel a chat turn, propose/confirm/cancel an action, read its result, and resume activity by cursor. Use HTTPS for request/response. Evaluate reusing the existing chat stream behind the companion boundary and introduce WebSocket only when needed for live events. Reconnect by fetching current state before resuming events. Version payloads and report supported capabilities so an older desktop can explain missing features.

| Record | Owner and behavior |
|---|---|
| Paired device | Desktop stores device ID, identity, granted scopes, creation/last seen and revocation. Phone stores the desktop identity and its protected credential. Unpair clears local credentials and associated cached data. |
| Conversation/message | Desktop stores stable thread/message IDs, ordering, text and delivery status. A client-generated message ID prevents duplicates. Retain existing history limits when building inference context. Decide thread deletion, retention and how to import the current page-local thread before release. |
| Action proposal/result | Desktop records requester, target, action, expiry, execution and result. Confirmation is single-use and bound to that device. If the reply is lost, query the result before offering retry. Report unknown when execution cannot be verified. |
| Activity event | Desktop assigns durable IDs and sequence cursors for delivery and deduplication. Keep lock-screen notification content minimal by default. |
| Attachments and location | Temporary by default. Set size, retention, export and explicit save rules per feature. Share location with accuracy, timestamp and expiry. Strip unnecessary metadata from photos and avoid persistent GPS trails by default. |

Keep remote permissions granular by feature. Test revocation during active connections, replay of pairing and confirmations, credential separation, payload limits and logs with no secrets. For later file exchange, use user-approved destinations, quotas and integrity checks. Expose no generic shell or unrestricted desktop action endpoint. A local connection can still invoke a configured cloud model, so display provider and data destination when relevant.

## 5. Android experience

| Surface | Design and behavior |
|---|---|
| Pair | Scan the QR on Windows, review the PC name and permission summary, then show the connection result. Explain how to get a new code when expired. Show a recovery path when the camera or network is unavailable. |
| Oracle | One PC status row and a clear chat entry point. Add the compact graphite and steel-blue orb as an indicator for idle, listening, thinking and speaking. Pair motion with accessible text and respect reduced motion. |
| Conversation | Desktop threads, streaming reply, stop, retry, and visible draft/send state. Offline shows saved history and drafts where available. Keep an unsent message available for review after reconnection. |
| Devices | Connection and last-seen state, approved actions and unpair. Confirmation states the exact action and target PC. Distinguish queued, launching, failed and unknown when supported. |
| Activity | Show action results and, later, workflow, reminder and file events. Deep-link to the item and indicate when information is stale. |
| Vision | Capture, retake and send a photo. Show identified text or product with expandable details, confidence wording and follow-up. |
| Atlas | Search, route preview and handoff. Label GPS source, accuracy and age. Route provider failure leaves a readable destination and retry option. A handed-off route recalculates from the current location when needed. |
| Settings | Connection and permissions, model/provider visibility, notification privacy, accessibility, cache controls and diagnostic export. |

For the first release, prioritize Oracle, Devices and Activity. Add Vision from the composer when shipped and give Atlas its own destination when route handoff is ready. Support dark and light appearance, large type, TalkBack labels, and touch targets appropriate to Android. Use text alongside status colors.

## 6. Validation and launch

- **Automated:** API contract and compatibility tests, migration tests, pair redemption and revocation, chat order and duplicate suppression, action state and retry behavior, malformed/oversized data, and Android state tests for loading and disconnect.
- **Hardware:** Physical Android with Windows on the same Wi-Fi. Test wrong network, Windows sleep, restarting Oracle, firewall denial, app background/restore, credential revocation and lost response after action execution. Include at least one smaller phone and large text.
- **Usability:** Use the proposed six-person study with Android/Windows users. Test pairing, expired QR recovery, shared chat, command outcome and offline interpretation. Aim for five of six to pair and find the correct thread without coaching. Fix every observed wrong-PC confirmation or false-success interpretation and retest. Treat the sample as formative.
- **Performance:** Measure time from launch to status, pairing completion, first reply token, action-result delay, reconnect, memory, battery and data use. Measure local and cloud inference separately. Set numerical budgets from the pilot and repeat on the lower-end test device.
- **Release:** Distribute a signed internal Android build with the compatible Windows build and setup notes. Document required versions, same-network setup, device revocation and recovery. Back up durable records before schema changes and exercise rollback. Collect diagnostics by consent with identifiers and payloads redacted.

## 7. First implementation backlog

1. Establish the build and integration environment and verify the desktop change workflow.
2. Define versioned request/result fixtures and implement the Android connection shell against them.
3. Add the desktop companion listener, scoped device registry and Windows pairing screen.
4. Implement Android pairing, trust persistence, status and disconnect/reconnect.
5. Add persistent desktop threads and connect both existing desktop chat and Android to them.
6. Add one scoped action, verify execution semantics, and show result in Activity.
7. Run the release scenario on hardware, record failures and repeat after fixes.

Decisions to settle before public release include conversation retention and migration, Android minimum version, desktop certificate rotation and recovery, distribution channel, and whether remote access warrants relay infrastructure. The initial scope assumes one paired PC per profile and keeps remote access out of the first milestone.
