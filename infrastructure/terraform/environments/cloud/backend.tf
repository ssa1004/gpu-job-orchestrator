# Terraform 백엔드 - S3 상태 저장 + DynamoDB 상태 잠금
# 사전 요구사항: S3 버킷, DynamoDB 테이블(LockID 파티션 키) 수동 생성 필요

terraform {
  backend "s3" {
    # 상태 저장용 S3 버킷
    # 계정 ID를 포함한 실제 버킷 이름으로 교체하세요
    bucket = "gwp-terraform-state"
    key    = "environments/cloud/terraform.tfstate"
    region = "us-west-2"

    # 상태 잠금용 DynamoDB 테이블
    dynamodb_table = "gwp-terraform-locks"

    # 저장 시 상태 파일 암호화
    encrypt = true

    # 암호화에 특정 KMS 키를 사용하려면 주석 해제:
    # kms_key_id = "alias/gwp-terraform"

    # 상태 접근에 특정 IAM 역할을 사용하려면 주석 해제:
    # role_arn = "arn:aws:iam::ACCOUNT_ID:role/gwp-terraform"
  }
}
