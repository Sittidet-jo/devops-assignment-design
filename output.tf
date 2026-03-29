output "server_public_ip" {
  description = "Public IP of k3s Server (Master)"
  value       = aws_instance.k3s_server.public_ip
}

output "agent_public_ips" {
  description = "Public IPs of k3s Agents (Workers)"
  value       = aws_instance.k3s_agent[*].public_ip
}
