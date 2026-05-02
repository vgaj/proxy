# Mobile Phone Proxy
This project gives you a way to route your browser's web traffic through an Android phone's mobile data connection. 
For example if you want to access content that is geo-restricted to a specific country, 
this is a simple alternative to a commercial VPN - provided you have a trusted contact there with an Android phone. 

## How it works
#### Components:
- The **Proxy Server** is a service that brokers connections between the browser and phone
- **Proxy Exit** is an Android app that runs on the phone
- Your **browser** needs to be configured to use the Proxy Server as an HTTP proxy

#### Flow:
1. The Proxy Exit app connects to the Proxy Server, making the phone available as a proxy exit node.
2. When the browser needs to make an HTTP request, it sends it to the Proxy Server.
3. The Proxy Server forwards the request to the phone over the established connection.
4. The phone fetches the content and returns it through the server to the browser.

```mermaid
flowchart BT
    B[Browser] -- 2 --> P[Proxy Server]
    M[Phone - Proxy Exit] -- 1 --> P
    P -- 3 --> M
    M -- 4 --> W[Website]
```

## Installation and usage

### 1. Proxy Server

Run this Docker command on a machine accessible from the internet:
```
docker run -d -p 8888:8888 -p 9999:9999 -e AUTH_CODE=XXXX registry.gitlab.com/viru7/proxy:latest
```

| Port | Purpose |
|------|---------|
| 8888 | Browser HTTP proxy connections |
| 9999 | Mobile app connections |

`XXXX` is your chosen password.

If the server is behind NAT (such as on a home network), ensure that port **9999** is forwarded to it. 
Also, confirm that you know both its public IP address and its internal (local) IP address.

### 2. Proxy Exit

On the phone navigate to the following URL and download the APK for the Proxy Exit app, and install it following any Android prompts to allow the installation: 
https://gitlab.com/viru7/proxy/-/jobs/13819560752/artifacts/browse/app/build/outputs/apk/release/

Open the app and enter the server's public IP address, port (`9999`), and your password, then tap **Start**.

### 3. Configure your browser

Set the browser's HTTP and HTTPS proxy to the server's internal IP address and port `8888`.
On Firefox this can be set directly.
For Chrome, this setting is typically inherited from the operating system. On Android, open your Wi-Fi settings, select your current network, tap the edit (pencil) icon, and expand Advanced options to locate it.

## FAQ
#### Why would I use this rather than a VPN for bypassing geo-restrictions?
It's simpler solution specially if it's the needs is temporary, and you have a trusted person that is willing to help.
Also unlike a VPN service, you control both ends of the connection: your contact runs the app on their phone and you connect through it directly, with no third-party servers involved beyond the relay you host yourself.

#### How do I build this?
Build from the command line with:
```
export ANDROID_HOME=/path/to/android/sdk
./gradlew assembleRelease
```

#### Do I have to use Docker to run the Proxy Server?
No. What is inside the docker image is actually just a single file Java class, so if you have Java installed you can also just run:
```
java ProxyServer.java
```