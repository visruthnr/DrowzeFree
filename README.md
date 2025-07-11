# DrowzeFree 😴📱  
A privacy-conscious Android app that detects **driver drowsiness** using real-time facial landmarks and alerts the user with alarms, vibration, and SOS messages — all while running in the background.

---

## 🚀 Features

- 🎯 Real-time detection of **closed eyes** (EAR) and **yawning** (MAR)
- 🧠 Uses **MediaPipe Face Landmarker** for facial landmark tracking
- 📣 Plays **alarm sound** and triggers **vibration** when drowsy
- 📍 Sends **SOS alerts with GPS location** to emergency contacts
- 🧾 Lets user **add/manage emergency contacts** in-app
- ⚙️ Runs as a **background foreground service** to keep detection active even when minimized
- 🧠 Uses lightweight Android services, no cloud dependencies

---

## 🧠 Tech Stack

| Technology                   | Purpose                                   |
|-----------------------------|-------------------------------------------|
| Android (Kotlin)            | App logic and UI                          |
| MediaPipe Face Landmarker   | Real-time eye/mouth landmark detection     |
| CameraX                     | Camera preview and frame capture          |
| SharedPreferences + Gson    | Local contact storage                     |
| SmsManager                  | Sends emergency SMS alerts                |
| FusedLocationProviderClient| Fetches GPS location                      |
| RecyclerView + Adapter      | Dynamic contact list                      |
| Notification / ForegroundService | Keeps detection alive in background   |

---

## 📱 Permissions Required

- `CAMERA` – for capturing face in real time  
- `SEND_SMS` – to send SOS messages  
- `VIBRATE` – to trigger physical alerts  
- `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` – for GPS  
- `FOREGROUND_SERVICE` – to run detection even when minimized

---

## 📦 How to Run

1. Clone the repo:
   ```bash
   git clone https://github.com/visruthnr/DrowzeFree.git
   ```

2. Open the project in **Android Studio**

3. Connect a real Android device via USB

4. Run the app and **grant all requested permissions**

5. Tap **“Manage Contacts”** to add emergency numbers

6. The app starts detecting automatically —  
   if drowsiness is detected continuously, **SOS will be triggered**



---





   


   
