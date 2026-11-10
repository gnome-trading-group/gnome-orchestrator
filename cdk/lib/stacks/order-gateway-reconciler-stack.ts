import * as cdk from 'aws-cdk-lib';
import * as events from 'aws-cdk-lib/aws-events';
import * as targets from 'aws-cdk-lib/aws-events-targets';
import * as iam from 'aws-cdk-lib/aws-iam';
import { Runtime } from 'aws-cdk-lib/aws-lambda';
import { SqsEventSource } from 'aws-cdk-lib/aws-lambda-event-sources';
import * as lambda from 'aws-cdk-lib/aws-lambda-nodejs';
import * as sqs from 'aws-cdk-lib/aws-sqs';
import { Construct } from 'constructs';
import { platformRegions, PRIMARY_REGION, SERVICE_ENDPOINTS } from '@gnome-trading-group/gnome-shared-cdk';
import { join } from 'path';
import { ORDER_GATEWAYS } from '../config';

interface Props extends cdk.StackProps {
  // The registry API's key, for the gateway rows; the API's URL it reads from SSM, where the registry publishes it.
  registryApiKeyId: string;
}

/**
 * The gateway reconciler (lambda/gateway_reconciler): the only thing that starts and stops live order gateways, in
 * every platform region, run one tick at a time. Its FIFO queue has one message group and the Lambda one concurrent run,
 * so ticks never overlap and a burst of wake-ups (sessions asking for gateways through the registry's gateways/ensure,
 * which sends to this queue by name) costs one tick; a schedule enqueues a tick every minute as the resync. One per
 * account, in the primary region, beside the registry and the service-endpoints table it reads.
 */
export class OrderGatewayReconcilerStack extends cdk.Stack {
  constructor(scope: Construct, id: string, props: Props) {
    super(scope, id, props);

    const queue = new sqs.Queue(this, 'GatewayReconcilerQueue', {
      queueName: ORDER_GATEWAYS.reconcilerQueue,
      fifo: true,
      // At least the Lambda's timeout (SQS triggers require it), and no more: a failed tick holds the one message group
      // until its batch is visible again.
      visibilityTimeout: cdk.Duration.minutes(2),
      retentionPeriod: cdk.Duration.hours(1),
      enforceSSL: true,
    });

    const fn = new lambda.NodejsFunction(this, 'GatewayReconciler', {
      functionName: 'GatewayReconciler',
      entry: join(__dirname, '..', '..', 'lambda', 'gateway_reconciler', 'reconciler.ts'),
      runtime: Runtime.NODEJS_20_X,
      timeout: cdk.Duration.minutes(2),
      memorySize: 256,
      // One tick at a time: each reads every gateway and writes its service.
      reservedConcurrentExecutions: 1,
      environment: {
        REGISTRY_API_KEY_ID: props.registryApiKeyId,
        GATEWAY_CLUSTER: ORDER_GATEWAYS.cluster,
        GATEWAY_BASE_FAMILY: ORDER_GATEWAYS.baseFamily,
        GATEWAY_CAPACITY_PROVIDER: ORDER_GATEWAYS.capacityProvider,
        GATEWAY_SERVICE_PREFIX: ORDER_GATEWAYS.servicePrefix,
        GATEWAY_ADDRESS_TAG: ORDER_GATEWAYS.addressTag,
      },
      bundling: {
        // The Node.js runtime has the AWS SDK.
        externalModules: ['@aws-sdk/*'],
      },
    });
    fn.addEventSource(new SqsEventSource(queue, { batchSize: 10 }));

    new events.Rule(this, 'GatewayReconcilerTick', {
      schedule: events.Schedule.rate(cdk.Duration.minutes(1)),
      targets: [new targets.SqsQueue(queue, {
        messageGroupId: ORDER_GATEWAYS.reconcilerGroup,
        message: events.RuleTargetInput.fromText(`tick:${events.EventField.time}`),
      })],
    });

    fn.addToRolePolicy(new iam.PolicyStatement({
      actions: ['apigateway:GET'],
      resources: [`arn:aws:apigateway:${PRIMARY_REGION}::/apikeys/${props.registryApiKeyId}`],
    }));
    fn.addToRolePolicy(new iam.PolicyStatement({
      actions: ['ssm:GetParameter'],
      resources: [
        `arn:aws:ssm:${PRIMARY_REGION}:${this.account}:parameter/gnome/orchestrator/latest-version`,
        `arn:aws:ssm:${PRIMARY_REGION}:${this.account}:parameter/gnome/registry/api-url`,
      ],
    }));
    // What each gateway says of itself: sessions linked, orders working.
    fn.addToRolePolicy(new iam.PolicyStatement({
      actions: ['dynamodb:GetItem'],
      resources: [`arn:aws:dynamodb:${SERVICE_ENDPOINTS.region}:${this.account}:table/${SERVICE_ENDPOINTS.tableName}`],
      conditions: { 'ForAllValues:StringLike': { 'dynamodb:LeadingKeys': ['order-gateway#*'] } },
    }));
    fn.addToRolePolicy(new iam.PolicyStatement({
      actions: ['ecs:DescribeServices', 'ecs:CreateService', 'ecs:UpdateService'],
      resources: platformRegions().map(region =>
        `arn:aws:ecs:${region}:${this.account}:service/${ORDER_GATEWAYS.cluster}/${ORDER_GATEWAYS.servicePrefix}*`),
    }));
    fn.addToRolePolicy(new iam.PolicyStatement({
      // Task definitions take no resource-level permission for these.
      actions: ['ecs:DescribeTaskDefinition', 'ecs:RegisterTaskDefinition', 'ec2:DescribeAddresses'],
      resources: ['*'],
    }));
    fn.addToRolePolicy(new iam.PolicyStatement({
      actions: ['ec2:AllocateAddress', 'ec2:CreateTags'],
      resources: platformRegions().map(region => `arn:aws:ec2:${region}:${this.account}:elastic-ip/*`),
    }));
    // The gateways' task and execution roles (OrderGatewayStack names them), for the services it creates.
    fn.addToRolePolicy(new iam.PolicyStatement({
      actions: ['iam:PassRole'],
      resources: [`arn:aws:iam::${this.account}:role/gnome-order-gateway-*`],
      conditions: { StringEquals: { 'iam:PassedToService': 'ecs-tasks.amazonaws.com' } },
    }));
  }
}
