# API Playback Framework - HTTP Interceptor

This component is part of the **API Playback Framework**, a developer tool designed to capture and replay HTTP API traffic for debugging, testing, and simulation purposes.

The `HTTPInterceptor` acts as a **forward proxy** that intercepts HTTP requests, records the traffic, and logs both request and response data. It enables developers to simulate API interactions without always relying on live backend services.

## ✨ Features

- Acts as a forward proxy to intercept outgoing HTTP requests
- Logs full request and response data (headers, body, status)
- Supports configurable modes: **Record** (capture live data) and **Replay** (future feature)
- Modular and extensible for HTTPS support and playback logic
- Suitable for simulating API responses and offline testing
