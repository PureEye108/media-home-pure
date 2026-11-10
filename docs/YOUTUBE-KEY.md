# Your own YouTube key (about 5 minutes, once)

[Tiếng Việt](YOUTUBE-KEY.vi.md)

The web remote's **Play first video** and **Search & pick a video** use YouTube's official search (YouTube Data API v3).
It needs an **API key**: a free code from Google that belongs to you. No key is built into Media Home Pure, so each user makes their own.
Everything else (the YouTube tile, Go in Brave, Search YouTube) works without a key.

**Cost**: free, no credit card. Google gives each key 10,000 units a day; one search uses 100, so about **100 searches a day**.

## 1. Make the key

1. On a computer, open **<https://console.cloud.google.com/>** and sign in with your Google account. Accept the terms if asked.
2. At the top, open the project list › **New project**. Name: `Media Home Pure` › **Create**. Make sure the new project is selected.
3. Menu ☰ › **APIs & Services › Library**. Search **YouTube Data API v3**, open it, tap **Enable**.
4. Menu ☰ › **APIs & Services › Credentials** › **+ Create credentials › API key**. Copy the key: it starts with `AIza` and has 39 characters.

## 2. Protect the key (recommended)

So that nobody else can use your key's quota, allow it only for this app and only for YouTube:

1. In **Credentials**, open the key (pencil icon).
2. **Application restrictions** › **Android apps** › **Add**:
   - **Package name**: `io.github.pureeye108.mediahomepure`
   - **SHA-1 certificate fingerprint**: `57:37:B4:31:04:38:DB:38:EF:E2:97:A0:3C:33:07:E1:A3:35:F4:89`

   These are the values for the official release APK. The app shows the values for *your* copy in the YouTube key dialog
   (if you built it yourself, use those).
3. **API restrictions** › **Restrict key** › tick **YouTube Data API v3**.
4. **Save**. Changes can take a few minutes.

## 3. Put the key in the app

Any one of these:

- On the tablet: **Settings › Set up › YouTube search from the web remote**, or **Settings › Web remote › YouTube key**. Paste › **Save**.
- In the web remote on your phone: tap **Play first video**; when it asks for the key, paste it › **Save key**.

The key is kept only on the tablet. To remove it: the same dialog › **Remove**.

## If it doesn't work

| Message | Meaning |
|---|---|
| The key is rejected | Typing error, the API isn't enabled, or the restrictions don't match (package name, SHA-1). Wait a few minutes after changes. |
| Today's free searches are used up | The 10,000 units are used; they reset at midnight Pacific time. |
| No results | Try other words. |

YouTube's API is subject to the [YouTube API Services Terms of Service](https://developers.google.com/youtube/terms/api-services-terms-of-service).
