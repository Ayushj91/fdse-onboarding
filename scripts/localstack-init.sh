#!/bin/bash
# LocalStack initialization — runs automatically when LocalStack is ready
# Creates the S3 bucket, SQS queues, and S3 event notification

set -e

echo "==> Initializing LocalStack resources..."

AWS_CMD="aws --endpoint-url=http://localhost:4566 --region us-east-1"

# Create S3 bucket
$AWS_CMD s3 mb s3://onboarding-bucket
echo "✓ S3 bucket created: onboarding-bucket"

# Create SQS queues
$AWS_CMD sqs create-queue --queue-name onboarding-queue
$AWS_CMD sqs create-queue --queue-name onboarding-dlq
echo "✓ SQS queues created"

# Get queue ARN for S3 notification
QUEUE_ARN=$($AWS_CMD sqs get-queue-attributes \
  --queue-url http://localhost:4566/000000000000/onboarding-queue \
  --attribute-names QueueArn \
  --query 'Attributes.QueueArn' \
  --output text)

# Set S3 event notification → SQS
$AWS_CMD s3api put-bucket-notification-configuration \
  --bucket onboarding-bucket \
  --notification-configuration "{
    \"QueueConfigurations\": [
      {
        \"QueueArn\": \"${QUEUE_ARN}\",
        \"Events\": [\"s3:ObjectCreated:*\"]
      }
    ]
  }"

echo "✓ S3 event notification configured → SQS"
echo "==> LocalStack initialization complete."

# Upload a sample file for quick demo
cat > /tmp/sample-customer.json << 'EOF'
{
  "note": "New customer from trade show",
  "contact": "Sarah Johnson, CTO at TechFlow Inc",
  "email": "sarah.johnson@techflow.io",
  "phone": "+44-20-7946-0958",
  "plan_interest": "ENTERPRISE",
  "address": "10 Downing Street, London, UK"
}
EOF

$AWS_CMD s3 cp /tmp/sample-customer.json s3://onboarding-bucket/customers/sample-001.json
echo "✓ Sample customer file uploaded to S3"
