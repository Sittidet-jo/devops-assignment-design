# Ansible Setup for Jenkins Shared Library

This directory contains Ansible playbooks to set up a Jenkins server and deploy this Shared Library project automatically.

## 1. Prerequisites & Installation

### On Your Local Machine (Control Node)

1.  **Install Ansible:**
    *   **macOS:** `brew install ansible`
    *   **Ubuntu/Debian:** `sudo apt update && sudo apt install ansible -y`

2.  **SSH Access:**
    Ensure you can SSH into the remote server using a private key (without a password).
    ```bash
    ssh-copy-id <user>@<server-ip>
    ```

## 2. Configuration

1.  **Edit Inventory:**
    Update `ansible/inventory.ini` with your server's IP and SSH user.
    ```ini
    [jenkins_servers]
    jenkins-host ansible_host=1.2.3.4 ansible_user=ubuntu
    ```

2.  **Edit Variables:**
    Update `ansible/group_vars/jenkins_servers.yaml` with your preferred paths, URLs, and passwords.

## 3. Running the Playbook

Before running the full setup, verify the connection to your server:

1.  **Verify Connection (Ping Test):**
    ```bash
    ansible jenkins_servers -i inventory.ini -m ping
    ```

2.  **Run Full Setup:**
    ```bash
    ansible-playbook -i inventory.ini site.yaml
    ```

## What it does

- **Base Setup:** Updates system and installs required packages (curl, git, etc.).
- **Docker Setup:** Installs Docker Engine and Docker Compose Plugin.
- **Jenkins Deployment:** 
    - Copies project files (Shared Library, JCasC, Dockerfile) to the server.
    - Builds the custom Jenkins image with all required tools (Python, Node, Trivy, etc.).
    - Configures Jenkins automatically using **Jenkins Configuration as Code (JCasC)**.
    - Registers this repository as a **Global Shared Library (Trusted)** with "Fresh clone per build" enabled.
    - Pre-configures all required Credentials and Environment Variables.

## Post-Installation

Once the playbook finishes:
1.  Access Jenkins at `http://YOUR_SERVER_IP:8080`.
2.  Login with:
    *   **User:** `admin`
    *   **Password:** `admin_password_change_me` (or what you set in `jcasc/jenkins.yaml`)
3.  Go to **Manage Jenkins > Credentials** to update the secret values for the pre-created credentials.
