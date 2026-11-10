# Security

Please report security problems privately with GitHub's **Report a vulnerability** button (Security tab of this repository), not in a public issue.

What the app is designed to do, so you can judge a report:

- The web remote listens on port 8080 **only for devices on the same local network** (private IPv4 addresses); others get HTTP 403.
- Every API call needs the PIN (4-8 digits, made up at random per device, changeable). After 10 wrong PINs an address is locked out for 10 minutes.
- The QR code carries the PIN after `#`, which browsers never send over the network.
- Player Pure's control interface is a non-exported ContentProvider: only this app can call it.
- No accounts, no analytics, no ads. Network use: Open-Meteo (weather, rounded location) and, only with your own key, the YouTube Data API.
- Release APKs are signed with one key. SHA-256 of the release certificate:
  `11:66:3F:D6:A8:C4:FC:57:5C:E3:BD:AC:38:2D:EA:EA:A9:99:7A:3D:57:B2:18:AC:4C:FA:EA:12:82:F6:67:24`
