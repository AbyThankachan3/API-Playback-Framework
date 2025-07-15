# HTTPInterceptor

**HTTPInterceptor** is a lightweight Java Spring Boot application that acts as an HTTP proxy server to intercept, inspect, and log HTTP traffic in real-time. It is designed for developers, testers, and security analysts who need to capture and analyze HTTP requests and responses flowing through their systems.

---

## Features

- **Proxy Server:** Runs an HTTP proxy to capture incoming and outgoing requests.
- **Full Request/Response Capture:** Logs headers, bodies, and metadata.
- **Elasticsearch Integration:** Persists intercepted logs in Elasticsearch for advanced querying and analysis.
- **Web UI:** Basic frontend to view logs and control recording state.
- **Spring Boot Powered:** Easy to configure and run.
- **Extensible:** Modular services for adding more storage backends or processing logic.

---
## Technology Stack

- **Backend:** Java 17+, Spring Boot
- **Proxy Server:** Custom servlet-based proxy implementation
- **Storage:** Elasticsearch, PostgreSQL
- **Frontend:** Thymeleaf template, static CSS
- **Build Tool:** Maven

---

## Prerequisites

- Java JDK 17+
- Maven 3.8+
- Elasticsearch instance running (locally or remotely)
- PostgreSQL instance running (locally or remotely)
---

## Installation & Setup

1. **Clone the repository:**
   ```bash
   https://github.com/AbyThankachan3/API-Playback-Framework.git

2. **Startup:**  
   When you run the application:
    - The **proxy server** starts on [http://localhost:8080](http://localhost:8080).
    - The **web frontend** loads on [http://localhost:9000](http://localhost:9000).

3. **Configure Proxy:**  
   To forward requests through the proxy and enable recording/replay:
    - Set your **system-wide proxy** or your tool’s proxy settings (e.g., Postman) to:
      ```
      Proxy Host: localhost
      Proxy Port: 8080
      ```

4. **HTTP vs HTTPS:**
    - For **HTTPS requests**, the proxy acts as a **tunnel**. It does not inspect or record HTTPS request bodies.
    - For **HTTP requests**, the full request and response bodies are **intercepted and recorded**.

5. **Recording & Replaying:**
    - Use the **web UI** at [http://localhost:9000](http://localhost:9000) to:
        - Enable or disable **recording mode**.
        - Switch to **replay mode**.
        - Configure **replay behavior**:
            - Match requests against the database **exactly**.
            - Use **vector search** for approximate matching.
            - Choose fallback or custom match settings.

6. **Replay Options:**
    - Customize whether to find an **exact match** only, allow approximate matches, or combine both strategies.
    - Recorded transactions can be replayed automatically based on your chosen configuration.

---

With this setup, you can intercept live HTTP traffic, inspect requests/responses, and control how recordings are matched and replayed for test or debugging scenarios.


