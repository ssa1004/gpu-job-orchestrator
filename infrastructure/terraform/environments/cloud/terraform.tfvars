# GPU Workload Platform - 클라우드 환경 설정
#
# 이 파일은 개발 클라우드 배포의 기본값을 포함합니다.
# 스테이징/프로덕션 환경용으로 복사하여 수정하세요.
#
# 프로덕션 환경에서 고려할 사항:
#   - eks_public_endpoint = false 설정
#   - eks_api_allowed_cidrs를 사무실/VPN CIDR로 제한
#   - GPU 노드 수 증가 및 모델 캐시 볼륨 활성화
#   - p3.2xlarge 또는 g5.xlarge 사용으로 최적화 성능 향상
#   - grafana_admin_password를 환경 변수로 강력한 비밀번호 설정

environment = "dev"
aws_region  = "us-west-2"

# 네트워크
vpc_cidr = "10.0.0.0/16"

# EKS 설정
kubernetes_version    = "1.29"
eks_public_endpoint   = true
# WARNING: 0.0.0.0/0 is for dev only. Restrict to office/VPN CIDRs before promoting to staging/prod.
eks_api_allowed_cidrs = ["0.0.0.0/0"]

# NVIDIA Device Plugin 버전 (GPU AMI 드라이버 버전과 일치해야 함)
# EKS GPU AMI AL2: NVIDIA 드라이버 530.x -> CUDA 12.1 -> Device Plugin 0.15.x
nvidia_device_plugin_version = "0.15.0"

# GPU 노드 설정
#
# g4dn.xlarge: 1x T4 GPU (16GB), 4 vCPU, 16GB RAM - On-Demand $0.526/시간
# g4dn.2xlarge: 1x T4 GPU (16GB), 8 vCPU, 32GB RAM - On-Demand $0.752/시간
# g5.xlarge: 1x A10G GPU (24GB), 4 vCPU, 16GB RAM - On-Demand $1.006/시간
#
# 개발 환경에서는 기본 테스트를 위해 On-Demand g4dn.xlarge 1대로 시작합니다.
gpu_instance_types      = ["g4dn.xlarge"]
gpu_spot_instance_types = ["g4dn.xlarge", "g4dn.2xlarge", "g5.xlarge"]

# On-Demand: 개발 환경의 상시 가용 용량
gpu_on_demand_desired = 1
gpu_on_demand_min     = 0
gpu_on_demand_max     = 3

# Spot: 개발 환경에서는 비활성화 (배치 테스트 시 활성화)
gpu_spot_desired = 0
gpu_spot_min     = 0
gpu_spot_max     = 5

# 모델 캐시 볼륨 (개발 환경에서는 비용 절감을 위해 비활성화)
enable_model_cache = false

# 스토리지
enable_efs = true

# 모니터링
# grafana_admin_password -- 환경 변수 TF_VAR_grafana_admin_password 로 설정하세요
# 예: export TF_VAR_grafana_admin_password="<strong-password>"
grafana_ingress_host   = ""

# 레지스트리
enable_nexus = true
