data "aws_ami" "amazon_linux" {
  most_recent = true
  owners      = ["amazon"]

  filter {
    name   = "name"
    values = ["al2023-ami-*-x86_64"]
  }
}

# ---- K3s Server (Master) ----
resource "aws_instance" "k3s_server" {
  ami                    = data.aws_ami.amazon_linux.id
  instance_type          = var.instance_type
  key_name               = var.key_name
  vpc_security_group_ids = [aws_security_group.k3s_sg.id]

  tags = {
    Name = "${var.instance_name}-server"
    Role = "server"
  }
}

# ---- K3s Agent (Worker) ----
resource "aws_instance" "k3s_agent" {
  count                  = var.agent_count
  ami                    = data.aws_ami.amazon_linux.id
  instance_type          = var.instance_type
  key_name               = var.key_name
  vpc_security_group_ids = [aws_security_group.k3s_sg.id]

  tags = {
    Name = "${var.instance_name}-agent-${count.index + 1}"
    Role = "agent"
  }
}
