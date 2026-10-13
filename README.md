# MailWatch for Android

Tell it in a chat what email you're waiting for. When that email lands in Gmail or Outlook, your phone rings with a full-screen alarm, like the clock app, until you stop it.

> **You:** Remind me when the HCL interview call mail comes
> **MailWatch:** Got it. I'll ring you when an email about an HCL / HCLTech interview arrives, including forwards from your placement cell. Ads and job digests won't count.

Everything runs on your phone. There's no server and no laptop to keep on. Gemini (free API key) understands your reminders and decides which new emails match.

The app has two tabs:
- **Chat** — tell it what to watch for, pause or delete reminders.
- **Arrived** — the emails that matched, with **Stop alarm** and **Open Gmail / Outlook**.

## Install on your phone

1. On your phone, open **https://github.com/rahulraghavendrar/mailwatch-android/releases/latest**
2. Tap **MailWatch.apk** to download it, then open the downloaded file.
3. If Android asks, allow Chrome to **install unknown apps**.
4. If Play Protect warns about an unknown app, tap **More details → Install anyway**. It warns because the app isn't from the Play Store.

To update later, install the newest APK the same way. Your reminders and inboxes are kept.

## First launch (about 5 minutes)

**1. Gemini key.** Get a free key at https://aistudio.google.com/apikey and paste it in. The app checks that it works.

**2. Inboxes.** Add every account where the email might land.

- **Gmail:** you need an *app password*, not your normal password.
  Turn on 2-Step Verification, then create one at https://myaccount.google.com/apppasswords.
- **Outlook / college Microsoft 365:** see [Connect Outlook](#connect-outlook) below.

**3. Permissions.** Allow all four so the alarm can reach you:

| Permission | Why |
| --- | --- |
| Notifications | The alarm can't appear without it |
| Alarms over the lock screen | Lets the alarm fill the screen, like the clock app |
| Exact timing | Mail checks happen on time |
| Unrestricted battery | Stops Android from pausing MailWatch in the background |

Then tap **Test the alarm** in Settings and lock your phone. 10 seconds later you'll see and hear exactly what a real alarm looks like.

## How the alarm behaves

- It rings on the **alarm volume**, so it sounds even when the phone is on silent, and comes through Do Not Disturb if your DND allows alarms (the Pixel default).
- **Stop** ends it. **Stop and open the email** opens Gmail or Outlook. **Remind me again in a few minutes** silences it for now.
- If you don't touch it, it rings for 3 minutes, pauses for 3, and rings again, up to 5 times. You can change these in Settings.
- One-time reminders ("when the HCL mail comes") stop watching after the first match. Ongoing ones ("every mail from the placement cell") keep going.
- When you add a reminder, MailWatch also checks the last 2 days of mail in case it already came.

## Connect Outlook

Microsoft requires every app that reads mail to have its own app ID. Making one is free:

1. Go to https://entra.microsoft.com and sign in. Try your **college account** first.
2. Open **App registrations → New registration**.
   - Name: `MailWatch`
   - Supported account types: **Accounts in any organizational directory and personal Microsoft accounts**
   - Redirect URI: leave empty
   - Tap **Register**
3. Copy the **Application (client) ID**.
4. Open **Authentication**. Under Advanced settings, set **Allow public client flows** to **Yes**, then **Save**.
5. Open **API permissions → Add a permission → Microsoft Graph → Delegated permissions**. Tick **Mail.Read**, then **Add**.
6. In the app, go to **Settings → Add Outlook**, paste the ID, and tap **Connect Outlook**.
7. Tap **Copy code and open**, paste the code on the Microsoft page, and sign in.

**If your college blocks this** ("you don't have access" or "need admin approval"), send your college mail to Gmail and let MailWatch watch Gmail instead:

- **Forwarding:** Outlook on the web → Settings → Mail → Forwarding → forward to your Gmail and keep a copy.
- **If forwarding to outside addresses is blocked:** use Power Automate (https://make.powerautomate.com, usually included with college Microsoft 365). Create an automated cloud flow: trigger **When a new email arrives (V3)**, action **Send an email (V2)** to your Gmail, with the original subject, and `From:` + sender + body as the body.

## How often it checks

Every 5 minutes by default (2, 5, 10 or 15 in Settings). When the phone sits idle for a long time, Android may stretch this to about every 10–15 minutes. A backup check every 15 minutes keeps running even after a restart. Opening the app also checks right away.

If an inbox stops working (password changed, Outlook sign-in expired), you get a notification so you aren't left thinking you're covered.

## Gemini usage and the free tier

New mail is checked in batches of up to 20 emails per request, and checks with no new mail cost nothing. If Gemini is out of quota or down, the app tries the backup model, then simple keyword matching, so a mail isn't silently missed.

## Privacy

- Your Gemini key, Gmail app password and Outlook sign-in are stored only in the app's private storage on your phone.
- Gemini sees the sender, subject and first ~700 characters of new emails. On the free tier, Google may use requests to improve its products.
- The app only reads mail. It never sends, deletes or marks anything as read.

## Building it yourself

GitHub Actions builds a signed APK on every push to `main` and publishes it to Releases. To build locally, open the project in Android Studio, or run `./gradlew assembleRelease`.

The signing key in `app/mailwatch.jks` is a personal sideloading key, kept in the repo so every build installs over the previous one. Don't use it for a Play Store release.

## Project layout

```
app/src/main/java/com/rahul/mailwatch/
  ChatEngine.kt     chat → Gemini → add / pause / remove reminders
  Gemini.kt         Gemini REST calls (chat, email matching)
  Checker.kt        reads new mail, matches it, keyword fallback, creates alerts
  GmailImap.kt      Gmail over IMAP (app password, read-only)
  Outlook.kt        Outlook over Microsoft Graph (device-code sign-in)
  Scheduler.kt      exact-alarm checks + WorkManager backup + reboot handling
  Alarms.kt         full-screen alarm notification, re-ringing, alarm sound
  AlarmActivity.kt  the full-screen alarm
  Store.kt          app storage
  ui/               Chat, Arrived, Settings and first-launch screens
```
