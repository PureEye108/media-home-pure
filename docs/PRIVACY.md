# Privacy

Media Home Pure has no account, no analytics, no ads and no tracking. It does not send anything to its author.

| What | Sent where | When |
|---|---|---|
| Location rounded to about 1 km (2 decimals of latitude/longitude) | [Open-Meteo](https://open-meteo.com/) | Weather update (every 30 minutes on the home screen), only if you allowed approximate location |
| A city name you type | Open-Meteo geocoding | When you search for a city in Settings › Weather |
| Your YouTube searches + your own API key | Google (YouTube Data API v3) | Only when you use Play first video / Search & pick, and only with your key |
| Nothing | - | Everything else stays on the device |

- **Location**: approximate only, never precise. The place name comes from Android's own geocoder.
- **Web remote**: a small web server on port 8080 that answers only devices on your local network, and only with the PIN.
  Nothing goes through the internet.
- **Notification access** is used only to see which app is playing music, and to control it. Notifications are not read or stored.
- **Accessibility** ("Media Home remote mouse & keyboard") only acts on commands from the web remote: it does not read the screen or listen to events.
- **All files access** is used by Player Pure to find music and by the web remote's Files tab. Files are not sent anywhere except to the phone that asks with the PIN.
- **Settings** (PIN, chosen apps, city, YouTube key) are stored in the app's private storage on the device. Uninstalling removes them.
