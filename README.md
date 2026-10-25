# TV Camera Overlay (tvcam)

Δείχνει **ζωντανή κάμερα, βίντεο, φωτογραφία, εικονίδιο ή μήνυμα πάνω σε ό,τι παίζει** σε Android TV, χωρίς να αλλάζει πρόγραμμα. Κάθε «slot» είναι ανεξάρτητο παράθυρο, οπότε ένα μόνιμο widget καιρού και μια κάμερα κουδουνιού μπορούν να φαίνονται ταυτόχρονα.

*Shows a live camera, video, photo, icon and/or message on top of whatever is playing on an Android TV. Home Assistant integration + companion TV app. Tested on a TCL Google TV (Android 12).*

Αποτελείται από δύο κομμάτια:

| Κομμάτι | Πού |
|---|---|
| Integration για Home Assistant (`tvcam.show`, `tvcam.hide`, `tvcam.test`) | `custom_components/tvcam` |
| Εφαρμογή για την TV (`com.pestpoint.radioauto.tvcam`) | `android/` · έτοιμο APK στα [Releases](../../releases) |

## Εγκατάσταση

### 1. Integration (HACS)
1. **HACS → ⋮ → Custom repositories** → `https://github.com/stathisktm/ha-tvcam`, τύπος **Integration**.
2. Βρες το **TV Camera Overlay** → **Download** → επανεκκίνηση Home Assistant.
3. **Ρυθμίσεις → Συσκευές & υπηρεσίες → Προσθήκη → TV Camera Overlay**.

Χρειάζεται το ενσωματωμένο integration **Android TV** (`androidtv`) ήδη ρυθμισμένο για την TV: οι εντολές πάνε μέσω του `androidtv.adb_command`.

### 2. Εφαρμογή στην TV
```bash
adb install -r camera-overlay.apk
adb shell appops set com.pestpoint.radioauto.tvcam SYSTEM_ALERT_WINDOW allow
```
Για κάμερες του Home Assistant (`camera_entity`) άνοιξε μία φορά την εφαρμογή στην TV και βάλε URL + long-lived token του HA. Για `video_url` / `image_url` / μηνύματα δεν χρειάζεται.

Δοκιμή: **Εργαλεία προγραμματιστή → Ενέργειες → `tvcam.test`**.

## Παράδειγμα: μόνιμος καιρός με κινούμενο εικονίδιο
```yaml
action: tvcam.show
target:
  entity_id: media_player.tv_adb
data:
  slot: weather
  icon_url: https://cdn.jsdelivr.net/gh/stathisktm/iris-weather-emoji@main/webp/sunny.webp
  icon_size: 32
  message: "22°C"
  text_layout: horizontal
  position: top_right
  margin: 2
  duration: 0
```
Τα εικονίδια είναι από το [Iris Weather Emoji](https://github.com/stathisktm/iris-weather-emoji). Τα κινούμενα WebP/GIF παίζουν (Android 9+).

## Πεδία του `tvcam.show`
`camera_entity`, `video_url` (HLS/RTSP/HTTP/YouTube), `image_url`, `image_path`, `title`, `message`, `icon_url`, `icon_size`, `duration` (0 = μόνιμο), `position`, `size`, `slot`, `margin`, `text_layout`, `text_position`, `text_background_opacity`, `rounded_corners`, `sound_url`, `chime`, `muted`, `ok_webhook_id` (κουμπί OK → webhook) κ.ά. Η πλήρης περιγραφή φαίνεται στο Home Assistant, στις Ενέργειες.

## Build της εφαρμογής
```bash
cd android
./gradlew :camera-overlay:assembleDebug
```
Χρειάζεται Android SDK (`local.properties` με `sdk.dir`) και JDK 17+.

## Δείτε επίσης
- [ha-tvpicture](https://github.com/stathisktm/ha-tvpicture): mode εικόνας με μία εντολή
- [ha-tvchannel](https://github.com/stathisktm/ha-tvchannel): ποιο κανάλι και πρόγραμμα παίζει τώρα

## Άδεια
[MIT](LICENSE)
