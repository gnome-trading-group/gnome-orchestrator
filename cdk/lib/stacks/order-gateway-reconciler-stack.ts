import * as cdk from 'aws-cdk-lib';
import * as cloudwatch from 'aws-cdk-lib/aws-cloudwatch';
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
      // The schedule's ticks can't carry a deduplication id; each tick's body names its minute, so it is its own id.
      contentBasedDeduplication: true,
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

    const tick = new events.Rule(this, 'GatewayReconcilerTick', {
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
        `arn:aws:ssm:${PRIMARY_REGION}:${this.account}:parameter/gnome/alerting/api-url`,
      ],
    }));
    // What each gateway says of itself: sessions linked, orders working.
    fn.addToRolePolicy(new iam.PolicyStatement({
      actions: ['dynamodb:GetItem'],
      resources: [`arn:aws:dynamodb:${SERVICE_ENDPOINTS.region}:${this.account}:table/${SERVICE_ENDPOINTS.tableName}`],
      conditions: { 'ForAllValues:StringLike': { 'dynamodb:LeadingKeys': ['order-gateway#*'] } },
    }));
    // What its alerts keep between ticks, on one item of its own.
    fn.addToRolePolicy(new iam.PolicyStatement({
      actions: ['dynamodb:GetItem', 'dynamodb:PutItem'],
      resources: [`arn:aws:dynamodb:${SERVICE_ENDPOINTS.region}:${this.account}:table/${SERVICE_ENDPOINTS.tableName}`],
      conditions: { 'ForAllValues:StringLike': { 'dynamodb:LeadingKeys': ['order-gateway-reconciler#*'] } },
    }));
    // Its alerts, onto gnome-registry's alert bus.
    fn.addToRolePolicy(new iam.PolicyStatement({
      actions: ['events:PutEvents'],
      resources: [`arn:aws:events:${PRIMARY_REGION}:${this.account}:event-bus/gnome-alerts`],
    }));
    fn.addToRolePolicy(new iam.PolicyStatement({
      actions: ['ecs:DescribeServices', 'ecs:CreateService', 'ecs:UpdateService'],
      resources: platformRegions().map(region =>
        `arn:aws:ecs:${region}:${this.account}:service/${ORDER_GATEWAYS.cluster}/${ORDER_GATEWAYS.servicePrefix}*`),
    }));
    // Which task a gateway runs as and why its tasks stopped, for its alerts.
    fn.addToRolePolicy(new iam.PolicyStatement({
      actions: ['ecs:ListTasks', 'ecs:DescribeTasks'],
      resources: ['*'],
      conditions: {
        ArnEquals: { 'ecs:cluster': platformRegions().map(region =>
          `arn:aws:ecs:${region}:${this.account}:cluster/${ORDER_GATEWAYS.cluster}`) },
      },
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

    // Named <Service>-<What>-<Severity>: gnome-registry's alert router takes the severity from the suffix and the
    // runbook (docs/runbooks/cloudwatch/GatewayReconciler.md) from the prefix.
    const alarm = (what: string, metric: cloudwatch.Metric, props: Partial<cloudwatch.CreateAlarmOptions>) =>
      metric.createAlarm(this, `GatewayReconciler${what}Alarm`, {
        alarmName: `GatewayReconciler-${what}-Error`,
        threshold: 1,
        evaluationPeriods: 5,
        datapointsToAlarm: 3,
        comparisonOperator: cloudwatch.ComparisonOperator.GREATER_THAN_OR_EQUAL_TO_THRESHOLD,
        treatMissingData: cloudwatch.TreatMissingData.NOT_BREACHING,
        ...props,
      });
    const minute = cdk.Duration.minutes(1);
    alarm('TickFailing', new cloudwatch.Metric({
      namespace: 'AWS/Events',
      metricName: 'FailedInvocations',
      dimensionsMap: { RuleName: tick.ruleName },
      statistic: 'Sum',
      period: minute,
    }), {
      alarmDescription: 'The minute schedule can\'t send the gateway reconciler its tick.',
    });
    alarm('NotRunning', fn.metricInvocations({ statistic: 'Sum', period: cdk.Duration.minutes(5) }), {
      alarmDescription: 'The gateway reconciler hasn\'t run for 5 minutes.',
      evaluationPeriods: 1,
      datapointsToAlarm: 1,
      comparisonOperator: cloudwatch.ComparisonOperator.LESS_THAN_THRESHOLD,
      treatMissingData: cloudwatch.TreatMissingData.BREACHING,
    });
    alarm('Errors', fn.metricErrors({ statistic: 'Sum', period: minute }), {
      alarmDescription: 'The gateway reconciler\'s runs keep failing.',
    });
    alarm('QueueStuck', queue.metricApproximateAgeOfOldestMessage({ statistic: 'Maximum', period: minute }), {
      alarmDescription: 'A message has waited 5 minutes on the gateway reconciler\'s queue: one failing holds its group.',
      threshold: 300,
      evaluationPeriods: 3,
      datapointsToAlarm: 3,
    });
  }
}
