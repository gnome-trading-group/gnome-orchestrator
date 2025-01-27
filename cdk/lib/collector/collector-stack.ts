import * as cdk from "aws-cdk-lib";
import * as s3 from "aws-cdk-lib/aws-s3";
import * as ec2 from "aws-cdk-lib/aws-ec2";
import * as ecrAssets from 'aws-cdk-lib/aws-ecr-assets';
import * as iam from 'aws-cdk-lib/aws-iam';
import * as logs from 'aws-cdk-lib/aws-logs';
import { Construct } from 'constructs';
import { COLLECTORS } from "./items";

export interface CollectorStackProps extends cdk.StackProps {
  dockerImage: ecrAssets.DockerImageAsset;
}

export class CollectorStack extends cdk.Stack {
  constructor(scope: Construct, id: string, props: CollectorStackProps) {
    super(scope, id, props);

    const bucket = new s3.Bucket(this, 'CollectorBucket', {
      bucketName: 'market-data-collector',
    });

    const vpc = new ec2.Vpc(this, 'CollectorVPC', {
      maxAzs: 2,
      natGateways: 0, // Avoid NAT Gateway costs
      subnetConfiguration: [
        {
          name: 'PublicSubnet',
          subnetType: ec2.SubnetType.PUBLIC,
        },
      ],
    });

    const logGroup = new logs.LogGroup(this, 'CollectorLogGroup', {
      logGroupName: '/collector/logs',
      retention: logs.RetentionDays.ONE_WEEK,
    });

    const securityGroup = new ec2.SecurityGroup(this, 'CollectorSecurityGroup', {
      vpc,
      allowAllOutbound: true,
    });
    securityGroup.addIngressRule(
      ec2.Peer.anyIpv4(),
      ec2.Port.tcp(22),
      'Allow SSH access any IP'
    );

    const role = new iam.Role(this, 'CollectorRole', {
      assumedBy: new iam.ServicePrincipal('ec2.amazonaws.com'),
    });
    role.addManagedPolicy(
      iam.ManagedPolicy.fromAwsManagedPolicyName('CloudWatchAgentServerPolicy')
    );
    role.addManagedPolicy(
      iam.ManagedPolicy.fromAwsManagedPolicyName('AmazonEC2ContainerRegistryFullAccess')
    );
    props.dockerImage.repository.grantRead(role)
    bucket.grantReadWrite(role);

    for (const item of COLLECTORS) {
      this.createEC2Instance(item, vpc, securityGroup, props.dockerImage, role, bucket);
    }
  }

  private createEC2Instance(
    item: any[],
    vpc: ec2.Vpc,
    securityGroup: ec2.SecurityGroup,
    dockerImageAsset: ecrAssets.DockerImageAsset,
    role: iam.Role,
    bucket: s3.Bucket,
  ) {
    const userData = ec2.UserData.forLinux();
    userData.addCommands(
        'echo "Running user data..."',
        'sudo yum update -y',

        // Install Docker
        'sudo amazon-linux-extras enable docker',
        'sudo yum install -y docker',
        'sudo service docker start',
        'sudo usermod -a -G docker ec2-user',

        // Install CloudWatchAgent
        'sudo yum install -y amazon-cloudwatch-agent',
        'sudo mkdir -p /etc/cloudwatch-agent',
        // Do not modify these indents. You will regret it.
        `cat <<EOF > /etc/cloudwatch-agent/config.json
{
  "logs": {
    "logs_collected": {
      "files": {
        "collect_list": [
          {
            "file_path": "/var/lib/docker/containers/*/*.log",
            "log_group_name": "/collector/logs",
            "log_stream_name": "{instance_id}",
            "timestamp_format": "%Y-%m-%d %H:%M:%S"
          }
        ]
      }
    }
  }
}
EOF`,

        // Start CloudWatchAgent
        'sudo /opt/aws/amazon-cloudwatch-agent/bin/amazon-cloudwatch-agent-ctl -a start',

        // Start Docker
        `$(aws ecr get-login --no-include-email --region ${this.region})`,
        `sudo docker pull ${dockerImageAsset.imageUri}`,
        `sudo docker run --shm-size=2gb
        -e "MAIN_CLASS=${item[1]}" 
        -e "PROPERTIES_PATH=collector.properties" 
        -e "LISTING_ID=${item[0]}"
        -e "BUCKET_NAME=${bucket.bucketName}"
        -d ` + dockerImageAsset.imageUri
    );

    // TODO: Only have a keypair on dev
    const keyPair = ec2.KeyPair.fromKeyPairName(this, 'DefaultKeyPair', 'DefaultKeyPair');

    const instance = new ec2.Instance(this, `MarketCollectorListingId${item[0]}-v2`, {
      vpc,
      userData,
      instanceType: ec2.InstanceType.of(
          ec2.InstanceClass.T2,
          ec2.InstanceSize.MICRO
      ),
      machineImage: ec2.MachineImage.latestAmazonLinux2(),
      instanceName: `MarketCollectorListingId${item[0]}`,
      securityGroup,
      role,
      keyPair,
    });

    return instance;
  }
}