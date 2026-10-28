# Gnaural VR

A Meta Quest app that plays a Gnaural isochronic pulse through headphones while the whole field of view
flashes red and black in step with it, for a number of minutes you choose. You use it with your eyes open.

The sound engine is a Kotlin translation of Gnaural's `BinauralBeatSoundEngine.java` (Bret Logan,
2008; the original is in `docs/upstream/`). Only the isochronic-pulse voice was translated.
Because it is derived from Gnaural, the whole app is GPL-2.0-or-later (see `LICENSE`).

**Warning:** this app flashes light across your whole field of view. Flicker between about 3 and
30 Hz can trigger seizures in people with photosensitive epilepsy, including people who have never
had one. Press B, or the Meta button, to stop at any time.

## Build and install

1. Turn on Developer Mode for the headset in the Meta Horizon phone app.
2. Plug the headset into the Mac with a USB-C data cable and accept "Allow USB debugging" in the
   headset.
3. Check that `adb devices` lists it.
4. Build and install:

   ```bash
   ./gradlew :app:installDebug
   ```

5. In the headset, open the app library and choose Unknown Sources, then Gnaural VR.

`./gradlew :app:testDebugUnitTest` runs the engine test: for a 200 Hz base and a 10 Hz beat, the left
channel has to come out at 205 Hz and the right at 195 Hz.

## Using it

Pick a mode (Visuals, Meditate, Dream, Focus, Sleep), adjust the sliders if you want, and press Start.
During a session the right thumbstick changes the pulse and the left one the pitch (both up and down),
A shows the panel, and B stops.

"beeps | binaural waves" picks the sound: Gnaural's isochronic pulse, or its binaural beat (base +
beat/2 in the left ear, base - beat/2 in the right). "L · R crossed" works with beeps only: the tone
moves from ear to ear each cycle with no gap, and the eye on the other side is lit, so the right ear
pairs with the left eye. No study tests this pairing; Pearson et al. 2016 (eLife) found that out-of-step
flicker between the eyes reads to the brain as double the rate, which is why each eye here flickers at
half the pulse rate.

Each mode uses the EEG band its state is associated with: alpha 10 Hz, theta 6 and 4 Hz, low beta 14
Hz, delta 2 Hz. The bands describe those states; the evidence that pulsing at a band's rate brings a
state on is weak (Ingendoh et al. 2023, PLOS ONE: 5 of 14 studies supported it). Sleep is sound only.

## Why it looks like this

- Eyes open, red on black: a headset gives about 100 nits, far less than the 5,520-lumen lamp used on
  closed eyes in the flicker study below, so the app follows Ganzflicker, the eyes-open red-and-black
  screen flicker (Königsmark, Bergmann and Reeder 2021, Cortex).
- Light on for 30% of each cycle, regular rhythm, 10 Hz by default: the settings that gave the strongest
  patterns in Amaya et al. 2023 (PLOS ONE, N = 20, funded by Lumenate).
- An isochronic pulse instead of a binaural beat: pulsed tones reportedly drive a stronger brain response
  than binaural beats (Schwarz and Taylor 2006, via secondary summaries).

## How it works

- `Engine.kt` makes the sound: one tone, switched on for the first 30% of each pulse with Gnaural's
  100-sample crossfade, and silence in between. A new pulse rate starts at the
  next pulse boundary, and `Rhythm` records when, so the flash can follow.
- `Audio.kt` streams that sound to an `AudioTrack` on its own thread, fading in and out over one second,
  and reads the track's timestamps to know which frame you are hearing right now.
- `MainActivity.kt` is a Meta Spatial SDK activity. A stereo 360° layer fills the view, one half per
  eye, and a per-tick system colours each half red or black from the frame you are hearing. It asks for a
  120 Hz display (or 80 Hz) so that a 10 Hz pulse divides into whole frames.

The fonts (Unbounded and Space Grotesk, from Google Fonts) are under the SIL Open Font License; see `LICENSE-fonts.txt`.
