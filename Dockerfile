FROM jenkins/jenkins:lts-jdk21

USER root

# Install Jenkins Plugins
RUN jenkins-plugin-cli --plugins \
    "configuration-as-code" \
    "lockable-resources" \
    "sonar" \
    "gitlab-plugin" \
    "gitlab-branch-source" \
    "pipeline-utility-steps" \
    "parameter-separator" \
    "timestamper" \
    "docker-workflow" \
    "docker-plugin" \
    "docker-java-api" \
    "htmlpublisher"

ENV CASC_JENKINS_CONFIG=/var/jenkins_home/jcasc/jenkins.yaml
ENV DOCKER_BUILDKIT=1

RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates curl gnupg2 lsb-release \
    git unzip wget jq \
    fonts-thai-tlwg \
    build-essential libssl-dev zlib1g-dev \
    libbz2-dev libreadline-dev libsqlite3-dev \
    libncursesw5-dev xz-utils tk-dev libxml2-dev libxslt1-dev libxmlsec1-dev libffi-dev liblzma-dev \
    && rm -rf /var/lib/apt/lists/*

ARG PYTHON_VERSION=3.12.12
ARG SONAR_SCANNER_VERSION=5.0.1.3006

ENV PYENV_ROOT="/opt/pyenv"
ENV PATH="$PYENV_ROOT/shims:$PYENV_ROOT/bin:$PATH"
RUN git clone https://github.com/pyenv/pyenv.git $PYENV_ROOT && \
    echo 'export PYENV_ROOT="$PYENV_ROOT"' >> /etc/profile.d/pyenv.sh && \
    echo 'export PATH="$PYENV_ROOT/bin:$PATH"' >> /etc/profile.d/pyenv.sh && \
    echo 'eval "$(pyenv init -)"' >> /etc/profile.d/pyenv.sh

RUN bash -lc "pyenv install ${PYTHON_VERSION} && pyenv global ${PYTHON_VERSION}" && \
    bash -lc "pip install --upgrade pip setuptools wheel reportlab"

RUN install -m 0755 -d /etc/apt/keyrings && \
    curl -fsSL https://download.docker.com/linux/debian/gpg \
      | gpg --dearmor -o /etc/apt/keyrings/docker.gpg && \
    echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] \
         https://download.docker.com/linux/debian \
         $(. /etc/os-release && echo $VERSION_CODENAME) stable" \
      > /etc/apt/sources.list.d/docker.list && \
    apt-get update && apt-get install -y --no-install-recommends \
      docker-ce-cli \
      docker-buildx-plugin \
      && rm -rf /var/lib/apt/lists/*

RUN curl -fsSL https://deb.nodesource.com/setup_22.x | bash - && \
    apt-get update && apt-get install -y --no-install-recommends nodejs && \
    corepack enable && corepack prepare yarn@stable --activate && \
    rm -rf /var/lib/apt/lists/*

RUN curl -s https://raw.githubusercontent.com/kubernetes-sigs/kustomize/master/hack/install_kustomize.sh \
    | bash && mv kustomize /usr/local/bin/

RUN curl -sSL -o /usr/local/bin/argocd \
    https://github.com/argoproj/argo-cd/releases/latest/download/argocd-linux-amd64 && \
    chmod +x /usr/local/bin/argocd

RUN TRIVY_VERSION=$(curl -sS "https://api.github.com/repos/aquasecurity/trivy/releases/latest" \
      | grep '"tag_name"' | cut -d'"' -f4 | sed 's/v//') && \
    wget "https://github.com/aquasecurity/trivy/releases/download/v${TRIVY_VERSION}/trivy_${TRIVY_VERSION}_Linux-64bit.deb" && \
    dpkg -i "trivy_${TRIVY_VERSION}_Linux-64bit.deb" && \
    rm "trivy_${TRIVY_VERSION}_Linux-64bit.deb" && \
    echo "Trivy installed: ${TRIVY_VERSION}"

RUN wget "https://binaries.sonarsource.com/Distribution/sonar-scanner-cli/sonar-scanner-cli-${SONAR_SCANNER_VERSION}-linux.zip" && \
    unzip "sonar-scanner-cli-${SONAR_SCANNER_VERSION}-linux.zip" -d /opt && \
    mv "/opt/sonar-scanner-${SONAR_SCANNER_VERSION}-linux" /opt/sonar-scanner && \
    ln -s /opt/sonar-scanner/bin/sonar-scanner /usr/local/bin/sonar-scanner && \
    rm "sonar-scanner-cli-${SONAR_SCANNER_VERSION}-linux.zip"

RUN npm i -g \
      semantic-release \
      @semantic-release/commit-analyzer \
      @semantic-release/release-notes-generator \
      @semantic-release/changelog \
      @semantic-release/npm \
      @semantic-release/git \
      @semantic-release/gitlab \
      @semantic-release/exec \
      conventional-changelog-conventionalcommits

RUN YQ_VERSION=$(curl -sS "https://api.github.com/repos/mikefarah/yq/releases/latest" \
      | grep '"tag_name"' | cut -d'"' -f4) && \
    curl -sL "https://github.com/mikefarah/yq/releases/download/${YQ_VERSION}/yq_linux_amd64" \
      -o /usr/local/bin/yq && chmod +x /usr/local/bin/yq && \
    echo "yq installed: ${YQ_VERSION}"

RUN curl -sL "https://github.com/wkhtmltopdf/packaging/releases/download/0.12.6.1-3/wkhtmltox_0.12.6.1-3.bookworm_amd64.deb" \
      -o /tmp/wkhtmltox.deb && \
    apt-get update && \
    apt-get install -y --no-install-recommends \
      fontconfig \
      libjpeg62-turbo \
      xfonts-75dpi \
      xfonts-base \
      libxrender1 \
      libxext6 && \
    dpkg -i /tmp/wkhtmltox.deb && \
    rm /tmp/wkhtmltox.deb && \
    rm -rf /var/lib/apt/lists/* && \
    wkhtmltopdf --version

ARG DOCKER_GID
RUN group_exists=$(getent group ${DOCKER_GID} | cut -d: -f1) && \
    if [ -z "$group_exists" ]; then groupadd -g ${DOCKER_GID} docker; \
    else groupmod -n docker "$group_exists" || true; fi && \
    usermod -aG docker jenkins || true

RUN chown -R jenkins:jenkins /opt/pyenv

RUN bash -lc "pip install reportlab python-docx openpyxl"

RUN mkdir -p /shared-cache/npm \
             /shared-cache/yarn \
             /shared-cache/pip \
             /shared-cache/go \
             /shared-cache/trivy \
             /shared-cache/sonar && \
    chown -R jenkins:jenkins /shared-cache

USER jenkins
