# GPU Workload Platform - 하이브리드 환경 설정
#
# 이 설정은 AWS(탄력적 GPU 스케일링)와 온프레미스 인프라(기본 GPU 용량)에
# 걸쳐 플랫폼을 배포합니다.
#
# 중요: 이 설정을 적용하기 전에 확인해야 할 사항:
#   1. AWS Site-to-Site VPN 또는 Direct Connect가 정상 작동 중인지 확인
#   2. 온프레미스 kubeconfig에 접근 가능한지 확인
#   3. 환경 간 NVIDIA 드라이버 버전이 일치하는지 확인:
#      - 클라우드 (EKS GPU AMI): NVIDIA 드라이버 530.x, CUDA 12.1
#      - 온프레미스: 모델 이식성을 위해 동일한 드라이버 버전 필수
#   4. 민감한 값은 환경 변수로 설정:
#      - TF_VAR_grafana_admin_password
#      - TF_VAR_harbor_admin_password

environment = "prod"
aws_region  = "us-west-2"

# 네트워크
vpc_cidr = "10.0.0.0/16"

# 온프레미스 VPN 연결
enable_vpn         = true
enable_vpn_bgp     = false
onprem_vpn_ip      = "203.0.113.1" # 실제 온프레미스 VPN 엔드포인트 IP로 변경
onprem_bgp_asn     = 65000
onprem_cidr_blocks = ["192.168.0.0/16"] # 실제 온프레미스 CIDR로 변경

# 온프레미스 Kubernetes 클러스터
onprem_kubeconfig_path    = "~/.kube/config"
onprem_kubeconfig_context = "onprem-gwp"
onprem_storage_class      = "local-path"

# EKS 설정
kubernetes_version    = "1.29"
eks_public_endpoint   = false # 하이브리드 프로덕션에서는 프라이빗 접근만 허용
eks_api_allowed_cidrs = []    # VPN을 통해서만 접근

# NVIDIA Device Plugin - 일관성을 위해 온프레미스 버전과 반드시 일치해야 함
# 양쪽 환경 모두 NVIDIA 드라이버 530.x (CUDA 12.1) 실행 필수
nvidia_device_plugin_version = "0.15.0"

# 클라우드 GPU 노드 - 버스트 용량
# 0에서 시작하여 온프레미스 용량이 부족하면 오토스케일
gpu_instance_types      = ["g4dn.xlarge", "g4dn.2xlarge", "p3.2xlarge"]
gpu_spot_instance_types = ["g4dn.xlarge", "g4dn.2xlarge", "g5.xlarge", "p3.2xlarge"]

# On-Demand: 유휴 시 0으로 축소, 최대 10개 노드까지 버스트
cloud_gpu_on_demand_desired = 0
cloud_gpu_on_demand_min     = 0
cloud_gpu_on_demand_max     = 10

# Spot: 비용 효율적인 버스트를 위한 적극적 스케일링
cloud_gpu_spot_desired = 0
cloud_gpu_spot_min     = 0
cloud_gpu_spot_max     = 20

# 모니터링 - 비밀번호는 TF_VAR_ 환경 변수로 설정 필수
# grafana_admin_password = "환경 변수로 설정"

# ECR 크로스 계정 접근 (공유 서비스 계정에서 배포하는 경우)
ecr_cross_account_ids = []

# 온프레미스 Harbor
harbor_external_url = "https://harbor.gwp.local"
# harbor_admin_password = "환경 변수로 설정"
harbor_storage_size = "500Gi"
