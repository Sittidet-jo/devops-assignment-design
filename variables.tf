variable "aws_region" {
  description = "AWS region"
  type        = string
  default     = "ap-southeast-1"
}

variable "instance_type" {
  description = "EC2 instance type"
  type        = string
  default     = "t3.micro"
}

variable "key_name" {
  description = "EC2 key pair name"
  type        = string
}

variable "instance_name" {
  description = "EC2 instance name prefix"
  type        = string
  default     = "devops-assignment-k3s"
}

variable "agent_count" {
  description = "Number of k3s agent (worker) nodes"
  type        = number
  default     = 2
}
