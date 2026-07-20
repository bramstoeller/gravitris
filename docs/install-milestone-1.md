# Installing the Gravitris "squish toy" on your phone

Milestone 1 · written for the client, not for an Android developer.

You do not need any developer tools, an account, or a cable if you would rather
not use one. Ten minutes, most of which is waiting for a file to copy.

---

## 1. What you are about to install

**One block falls into an empty well. You move it, drop it, and watch it
squash.** That is the whole thing, and it is the whole thing on purpose.

There is no score, no lines to clear, no way to win and no way to lose. There
are no menus and no settings screen. If you find yourself waiting for the game
to start — it has. This build exists to answer a single question, and everything
that is not that question has been left out so it cannot get in the way of the
answer.

**The question is: does the block feel heavy?**

Not "is this fun yet" and not "does it look finished" — it will not look
finished, because the artwork deliberately has not been done yet. Flat colours,
no texture, no glow. If the weight is not right, the artwork would only disguise
that, and we would find out much later and much more expensively.

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
when a block lands, which is a large part of what makes it feel heavy — so it is
not decoration, it is the thing you are being asked to judge. Vibration is what
Android calls a "normal" permission: it grants no access to any of your data,
cannot read anything, and cannot reach the network. The worst it can do is buzz
the phone.

One thing worth knowing so it does not surprise you: **Android does not list
normal permissions like this on the install screen.** You will not see vibration
mentioned anywhere during installation. That is standard Android behaviour, not
something being hidden — we are telling you here because the install screen will
not.

We previously described this app as having "no permissions". That was accurate
when it was written and is not accurate now, so we are correcting it rather than
letting it stand. The claim that matters — **no internet access** — is unchanged
and still holds.

---

## 4. Playing with it

Touch anywhere on the screen. You do not need to touch the block itself.

| To do this | Do this |
| ---------- | ------- |
| Move the block sideways | Drag anywhere on the screen |
| Turn the block | Tap |
| Drop it hard, now | Flick downwards |

When a block has landed and stopped moving, you are given another one. The well
fills up as you play; when it reaches the top it empties and you start again.
Nothing is being scored and nothing is being lost — it is a sandbox.

**Flick down at least a few times.** A block that falls on its own lands gently;
a hard flick lands hard, squashes more and thumps harder. That difference is
most of what we are asking you to judge, and you will not see it if every drop
is a gentle one.

### What we would like to know

Roughly in order of how much it matters:

1. **Does it feel heavy?** When a block lands, does it land like something with
   weight, or like a picture of something with weight?
2. **Does the thump match what you see?** The vibration is scaled to how hard
   the landing was. Do a gentle landing and a hard one feel different from each
   other — and does each one feel like the right amount for what happened on
   screen?
3. **Does the squash look right?** Too much, too little, too bouncy, too stiff?
4. **Does dragging feel direct** — like you are pushing the block, or like you
   are asking it to move and waiting?
5. **Is the wait between blocks annoying?** A block takes roughly three and a
   half seconds from appearing to being replaced, most of it falling.

Trust your first reaction on all five. You are the instrument here, and a
considered second opinion is worth less to us than what you noticed in the first
ten seconds.

---

## 5. The numbers in the bottom-left corner

There is a small block of grey text in the bottom-left. It is instrumentation
for us and it will not be in the finished game. **This is the other reason this
build exists**: we have no phone in our build environment, so your handset is
the only measuring instrument this project has.

It starts with `milestone1 floor - not a verdict`. That line is there on purpose.
The numbers below it are honest, but they measure a game with the artwork
missing, so a good number means "nothing is structurally wrong yet" rather than
"this will be fast enough". Please keep that line in shot if you photograph it —
it stops the number being quoted later as more than it is.

### Three things to send us

**A. Normal numbers.** A photo of that corner at three moments: an empty well,
a half-full well, and a full one. The `bodies` figure tells us which is which.

**B. The shading comparison.** With a full well, **press volume up**. The
`shade:` line flips between `on` and `off`, and the block colours stop
responding to being squashed. Photograph the numbers again. The difference
between the two is the cost of one small visual effect, and it is our only
estimate of what the full artwork will cost later.

**C. The benchmark — the important one.** **Press volume down.**

The game will freeze for a few seconds and the text will say `measuring, hold
still…`. That freeze is the measurement, not a crash. When it finishes, several
extra lines appear starting with `solver bench - cpu only, no gpu`. Photograph
them.

That block is the single most valuable thing in this build for us. It is the
same physics workload we have been measuring on our build machines all along,
run on real hardware for the first time. Until now, every performance estimate
in this project has rested on a guess about how much slower a phone is than a
server — a guess we have been carrying with a range so wide it spans
"comfortable" and "barely fits". Your `x host` figure replaces that guess with a
fact.

You can press volume down again later if you want; it will re-measure.

---

## 6. If something goes wrong

| What you see | What it means |
| ------------ | ------------- |
| **A black screen, nothing moves** | The graphics code failed to start. Please tell us — this is the one failure we could not test for from here, and it is important. |
| **It freezes for a few seconds** | Expected, if you pressed volume down. That is the benchmark running. |
| **No vibration at all** | Check the phone is not on silent and that system haptics are enabled. If they are, tell us — the text ends in `haptics:scaled` or `haptics:fixed`, and which one it says tells us whether it is your hardware or our settings. |
| **Blocks pass through each other, or jitter in place** | Please say which, and send a photo or a short video. These look similar and have completely different causes. |
| **The app closes by itself** | Tell us what you had just done. |

Nothing you can do in this build can damage anything or leave any trace on the
phone beyond the app itself. Uninstalling it removes everything: hold the icon,
then **Uninstall**.

---

## 7. Uninstalling

Hold the Gravitris icon in the app drawer, then choose **Uninstall**. There is no
data stored anywhere else and nothing to clean up afterwards.
