# Dockerized Jenkins with Comprehensive Caching and Tooling

This project provides a ready-to-use, dockerized Jenkins setup equipped with a wide range of essential DevOps tools and a robust caching mechanism to accelerate your CI/CD pipelines.

By mapping the Docker socket and correctly configuring user permissions, this setup allows Jenkins to run Docker commands natively (Docker-in-Docker). It also uses named volumes to persist caches for `npm`, `pip`, and `Trivy`, ensuring that dependencies and vulnerability databases are not re-downloaded on every run.

## 1. Prerequisites

- Docker and Docker Compose installed on your host machine.
- Your user account must be part of the `docker` group on the host.

## 2. Setup and Configuration

### Step 1: Find the Docker Group GID on Your Host

Before launching Jenkins, you need to find the Group ID (GID) of the `docker` group on your host machine. This is crucial for granting the Jenkins container the correct permissions to use the Docker socket.

Run the following command **on your host machine** (not inside a container):

```bash
getent group docker
```

You will see an output similar to this:

```
docker:x:123:your-user
```

The third field is the **GID**. In this example, it's `123`. We will use this ID to configure our Jenkins container.

For convenience, you can export this GID as an environment variable:

```bash
export DOCKER_GID=$(getent group docker | cut -d: -f3)
echo "Your Docker GID is: $DOCKER_GID"
```

The `docker-compose.yaml` file is configured to automatically use this environment variable.

### Step 2: Review the Configuration Files

This project includes the following key files:

- **`docker-compose.yaml`**: Orchestrates the Jenkins service, defines persistent volumes for home and caches, and passes the `DOCKER_GID` to the Docker build.
- **`Dockerfile`**: Defines the custom Jenkins image, installing all the necessary tools like `pyenv`, `Node.js`, `Docker CLI`, `Kustomize`, `ArgoCD CLI`, `Trivy`, `Sonar Scanner`, and more. It uses the `DOCKER_GID` to map the `jenkins` user to the host's `docker` group.

### Step 3: Launch Jenkins

With the `DOCKER_GID` exported, you can now launch the Jenkins service in the background:

```bash
docker compose up -d
```

Jenkins will start, and you can access it at `http://<your-host-ip>:8080`.

### Step 4: Initial Jenkins Login

The first time you start Jenkins, you'll need an initial admin password to unlock it. You can retrieve it with this command:

```bash
docker exec jenkins cat /var/jenkins_home/secrets/initialAdminPassword
```

Copy the password from the terminal, paste it into the Jenkins setup wizard in your browser, and follow the on-screen instructions to complete the setup.

## 3. Verifying the Setup

### Docker Access

To confirm that the `jenkins` user inside the container can communicate with the Docker daemon on the host, run:

```bash
docker exec -it jenkins bash
docker version
```

If you see both **Client** and **Server** information, the setup is successful. The GID mapping has worked, and you will not need to manually `chmod` the Docker socket.

### Caching

The `docker-compose.yaml` file defines named volumes for caching:
- `jenkins_npm_cache`
- `jenkins_pip_cache`
- `jenkins_trivy_cache`

These volumes are managed by Docker and will persist even if you remove and recreate the Jenkins container. This means that subsequent builds that use `npm`, `pip`, or `Trivy` will be significantly faster as they will reuse the downloaded data from the cache.

## 4. Summary of the Solution

- **GID Mapping for Docker Socket**: By using `getent group docker` on the host and passing the GID to the container via `group_add` and build arguments, we securely grant the `jenkins` user permission to use the Docker socket without resorting to insecure `chmod 666` commands.
- **Persistent Caching**: Using named Docker volumes for `~/.npm`, `~/.cache/pip`, and `~/.cache/trivy` inside the Jenkins home directory ensures that build and scan caches persist across container restarts, leading to faster and more efficient pipelines.
- **Comprehensive Tooling**: The custom `Dockerfile` pre-installs a wide array of modern DevOps tools, making the Jenkins environment ready for complex CI/CD workflows out of the box.
