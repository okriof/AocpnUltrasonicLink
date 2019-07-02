# AocpnUltrasonicLink
Proof-of-concept Android app for linking Calypso Ultrasonic wind data to the android app version of OpenCPN (aocpn).
This app connects to the Calypso Ultrasonic Portable via Bluetooth to get apparent wind speed and angle, as well as air temperature, compass heading and heel/pitch.
Those measurements are sent as NMEA messages via UDP on the local port 12245 (127.0.0.1:12245).
This app also listens for position NMEA messages via UDP on port 12246 (127.0.0.1:12246). If boat speed and coarse is received, true wind angle, speed and direction is calculated and sent as NMEA together with the other measurements.

