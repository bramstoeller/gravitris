# Installing Gravitris on your phone

Written for you, not for a developer. No accounts, no cable required if you'd
rather not use one. Ten minutes, most of which is waiting for a file to copy.

---

## 1. What you're about to install

This is the real game now — not the single falling block from before.

Pieces drop into the well one at a time: heavy, squashy gel blocks that
squash flat when they land and pile up underneath the next one. Nothing
snaps into a grid. The pile sags and leans under its own weight as it grows,
the way a stack of something soft and heavy actually would.

Pack a horizontal band of the well full enough and it starts glowing warmly
from within — the fuller it gets, the brighter and more urgent the glow. Once
it's full enough, it lights up, the material dissolves, and everything above
it drops and resettles. That drop-and-resettle is the payoff moment; it's
worth watching rather than rushing past.

You can lose. If the pile reaches the very top of the well and stays there,
the run ends — a message says so, and one tap starts a fresh well. A hard
landing that briefly bulges the pile up to the top does **not** end the run
by itself; it only ends if the pile is genuinely still up there a moment or
two later.

**Controls**, unchanged from anything you've already seen:

| To do this | Do this |
| ---------- | ------- |
| Move a piece sideways | Drag anywhere on the screen |
| Turn a piece | Tap anywhere |
| Drop it hard, now | Swipe down |

There's no title screen and no menu — the app opens straight into a well with
the first piece already falling.

---

## 2. Getting it onto the phone

You will have been sent one file, `app-debug.apk`.

1. **Copy it to the phone.** A USB cable and drag-and-drop works. So does
   emailing it to yourself, or any file-sharing app — it does not matter how it
   gets there.
2. **Open it on the phone**, using the Files app or by tapping the download
   notification.
3. **Android will interrupt you with a warning** about installing apps from
   outside the Play Store. Read the next section before tapping anything, then
   allow the install.
4. It appears in your app drawer as **Gravitris**. Tap it.

### About that warning

Android blocks installs from outside the Play Store by default, and it will ask
you to confirm before allowing one. Depending on your Android version it says
something like *"For your security, your phone is not allowed to install unknown
apps from this source"*, with a button to allow it.

**This is normal and expected.** It is not a virus warning and it is not Android
noticing anything about this particular app. It is a single setting about *where
software came from*, and it appears identically for every app installed outside
a store — including ones from companies you would trust. You are being asked
because Android cannot vouch for the source, not because it has found a problem.

If it helps: you are the source. This is a build made for you, sent to you, and
nobody else has it.

Once you have allowed it for the app you installed from (usually Files or your
browser), the warning will not repeat for that app. You can turn the permission
back off afterwards if you prefer — it is under
**Settings → Apps → Special app access → Install unknown apps**.

---

## 3. What this build can and cannot do on your phone

**It has no internet access.** The app cannot reach the network. This is not a
promise about our intentions — it is enforced by the Android system itself. An
app can only use the network if it declares the `INTERNET` permission, this app
does not declare it, and the operating system refuses network access to apps
that have not asked for it. There is also an automated check in our build that
fails the build outright if that permission ever appears, including if it were
pulled in indirectly by someone else's code.

So: no data leaves your phone, because there is no route for it to leave by.
There are no accounts, no analytics, no crash reporting and no servers.

**It does ask for one permission: vibration.** That is what produces the thump
when a piece lands, which is a large part of what makes it feel heavy — so it is
not decoration, it is a thing you're being asked to judge. Vibration is what
Android calls a "normal" permission: it grants no access to any of your data,
cannot read anything, and cannot reach the network. The worst it can do is buzz
the phone.

One thing worth knowing so it does not surprise you: **Android does not list
normal permissions like this on the install screen.** You will not see vibration
mentioned anywhere during installation. That is standard Android behaviour, not
something being hidden — we are telling you here because the install screen will
not.

We previously described this app as having "no permissions". That was accurate
when it was written and is not accurate now, so we correct it rather than
letting it stand. The claim that matters — **no internet access** — is unchanged
and still holds.

---

## 4. A few things worth knowing before you play

- **Every piece is the same soft square shape, just a different colour.**
  Shape variety (the way Tetris has different pieces) isn't built yet — that's
  a deliberate simplification for now, not a bug.
- **There's no score on screen yet.** When a run ends you'll see a short
  message and can tap to start again immediately — nothing is being counted or
  saved beyond that.
- **The back button pauses the game** rather than closing the app — press it
  again to resume. Use your phone's home button or gesture to leave the app.
- There's a small, low-opacity block of grey text in the bottom-left corner.
  That's a frame-rate readout left over from earlier testing — real numbers,
  not part of the game. Safe to ignore; if you're curious what it means or it
  looks like it's dropping badly while you play, mention it, a screenshot
  helps.

---

## 5. What we'd like to know

Roughly in order of how much it matters:

1. **Does the pile feel heavy?** As it grows, does it sag and settle under its
   own weight, or does it feel like it's just stacking up without any weight
   behind it?
2. **Does the glow give you fair warning?** Watch a band as you fill it — does
   the warmth build in a way that tells you "this one's close" before it
   actually clears, or does the clear surprise you?
3. **Does a full band clearing feel satisfying?** The material dissolves and
   everything above it drops. Does that read as a release, worth a beat to
   watch — or does it feel abrupt, or like nothing happened?
4. **Does losing feel fair?** You should only lose because the pile genuinely
   stayed too high, never because of a bounce off a hard landing that would
   have settled back down a moment later. If a run ever ends and it feels like
   it "shouldn't have," that's exactly the kind of thing to tell us — one
   honest caveat below is relevant here.
5. **How does the difficulty feel?** How full a band needs to be before it
   clears is our current best guess, not a finished decision — too easy and
   the game never builds any tension; too hard and it never clears. Tell us
   which way it leans.

Trust your first reaction on all five. You are the instrument here, and a
considered second opinion is worth less to us than what you noticed as you
played.

---

## 6. Honest caveats

- **The clear threshold (how full a band needs to be) is a provisional
  starting guess**, set from one internal play-through, not a tuned design.
  It is meant to move once we hear from you — if bands feel like they clear
  too easily or never fill up, that's the dial we'll turn.
- **Losing currently has no warning glow.** The design calls for the danger
  zone at the top of the well to flash and pulse before a run ends, so you
  can see it coming — that visual isn't wired up yet. Right now the game
  quietly gives a struggling pile a second or two to settle, and either play
  continues or the run ends, with nothing on screen marking that grace
  period. If a loss feels sudden, that is this missing piece, not the
  underlying rule being unfair — tell us either way.
- **On your phone specifically, the landing thump is a fixed buzz, not one
  that scales with how hard a piece lands.** Some phones can vary how strong a
  vibration is; yours is not one of them, as far as Android reports back to
  us. That's a limitation of your hardware, not something we chose or can fix
  from our end — the visual squash and the sound of the impact should still
  carry the weight even without the vibration scaling.
- **Performance is yours to judge, honestly for the first time.** We have no
  phone and no graphics hardware in our own build environment, so every
  performance number we've had until now was a guess about how much slower a
  phone is than a development machine. The frame-rate number in the corner is
  the real thing, measured on your actual hardware — if the game stutters or
  feels choppy, that is a genuine finding, not noise.
- **The losing sequence itself (well fills up, brief grace period, game over,
  tap to restart) has only been tested by us in automated tests, never on a
  real phone.** Yours is the first time it runs on hardware end to end, so
  pay attention to that path specifically.

---

## 7. If something goes wrong

| What you see | What it means |
| ------------ | ------------- |
| **A black screen, nothing moves** | The graphics code failed to start. Please tell us — this is one of the harder things to test from our end. |
| **The screen suddenly freezes for a few seconds** | If you pressed one of the volume buttons, that's expected — they trigger internal test tools (a shading toggle and a performance check) left in from development. Otherwise, tell us. |
| **No vibration at all** | Check the phone is not on silent and that system haptics are enabled. If they are, tell us. |
| **Pieces pass through each other, or jitter in place** | Please say which, and send a photo or a short video. These look similar and have different causes. |
| **The game ends and you don't think it should have** | See the caveat above about the missing warning glow — tell us what it looked like right before. |
| **The app closes by itself** | Tell us what you had just done. |

Nothing you can do in this build can damage anything or leave any trace on the
phone beyond the app itself. Uninstalling it removes everything.

---

## 8. Uninstalling

Hold the Gravitris icon in the app drawer, then choose **Uninstall**. There is no
data stored anywhere else and nothing to clean up afterwards.
