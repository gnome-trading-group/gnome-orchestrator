import * as cdk from 'aws-cdk-lib';
import { Match, Template } from 'aws-cdk-lib/assertions';
import { PRIMARY_REGION } from '@gnome-trading-group/gnome-shared-cdk';
import { OrderGatewayReconcilerStack } from '../lib/stacks/order-gateway-reconciler-stack';
import { ORDER_GATEWAYS } from '../lib/config';

describe('the gateway reconciler stack', () => {
  const app = new cdk.App();
  const stack = new OrderGatewayReconcilerStack(app, 'Reconciler', {
    env: { account: '123456789012', region: PRIMARY_REGION },
    registryApiKeyId: 'key-id',
  });
  const template = Template.fromStack(stack);

  it('takes the schedule\'s ticks, which carry no deduplication id of their own', () => {
    template.hasResourceProperties('AWS::SQS::Queue', {
      QueueName: ORDER_GATEWAYS.reconcilerQueue,
      FifoQueue: true,
      ContentBasedDeduplication: true,
    });
  });

  it('ticks into the reconciler\'s one message group, a different body each minute', () => {
    template.hasResourceProperties('AWS::Events::Rule', {
      ScheduleExpression: 'rate(1 minute)',
      Targets: [Match.objectLike({
        SqsParameters: { MessageGroupId: ORDER_GATEWAYS.reconcilerGroup },
        InputTransformer: Match.objectLike({ InputPathsMap: { time: '$.time' } }),
      })],
    });
  });
});

describe('the gateway reconciler\'s access', () => {
  const app = new cdk.App();
  const template = Template.fromStack(new OrderGatewayReconcilerStack(app, 'Access', {
    env: { account: '123456789012', region: PRIMARY_REGION },
    registryApiKeyId: 'key-id',
  }));
  const statements = JSON.stringify(template.findResources('AWS::IAM::Policy'));

  it('names both modes\' base task definitions and capacity providers, and the market-data key', () => {
    template.hasResourceProperties('AWS::Lambda::Function', {
      Environment: { Variables: Match.objectLike({
        GATEWAY_LIVE_BASE_FAMILY: ORDER_GATEWAYS.modes.live.baseFamily,
        GATEWAY_LIVE_CAPACITY_PROVIDER: ORDER_GATEWAYS.modes.live.capacityProvider,
        GATEWAY_PAPER_BASE_FAMILY: ORDER_GATEWAYS.modes.paper.baseFamily,
        GATEWAY_PAPER_CAPACITY_PROVIDER: ORDER_GATEWAYS.modes.paper.capacityProvider,
        MARKET_DATA_API_KEY_SECRET: 'gnome/api-keys/market-data',
      }) },
    });
  });

  it('reads where the market-data API is, for paper gateways', () => {
    expect(statements).toContain('parameter/gnome/market-data/api-url');
  });

  it('may raise the live and the paper host groups in every region', () => {
    for (const region of ['us-east-1', 'eu-west-1', 'ap-northeast-1']) {
      expect(statements).toContain(`autoScalingGroupName/*-OrchestratorOrderGatewayStack-${region}-GatewayHosts*`);
      expect(statements).toContain(`autoScalingGroupName/*-OrchestratorOrderGatewayStack-${region}-GatewayPaperHosts*`);
    }
  });

  it('may pass both gateway task roles', () => {
    expect(statements).toContain(':role/gnome-order-gateway-*');
  });
});

describe('the gateway reconciler\'s alarms', () => {
  const app = new cdk.App();
  const template = Template.fromStack(new OrderGatewayReconcilerStack(app, 'Alarms', {
    env: { account: '123456789012', region: PRIMARY_REGION },
    registryApiKeyId: 'key-id',
  }));

  it('are named so the alert router finds their runbook and severity', () => {
    for (const what of ['TickFailing', 'NotRunning', 'Errors', 'QueueStuck']) {
      template.hasResourceProperties('AWS::CloudWatch::Alarm', { AlarmName: `GatewayReconciler-${what}-Error` });
    }
  });

  it('count a silent reconciler as not running', () => {
    template.hasResourceProperties('AWS::CloudWatch::Alarm', {
      AlarmName: 'GatewayReconciler-NotRunning-Error',
      MetricName: 'Invocations',
      ComparisonOperator: 'LessThanThreshold',
      TreatMissingData: 'breaching',
    });
  });

  it('lets it keep its alert state on its own item and raise alerts on the registry\'s bus', () => {
    const statements = JSON.stringify(template.findResources('AWS::IAM::Policy'));
    expect(statements).toContain('order-gateway-reconciler#*');
    expect(statements).toContain('event-bus/gnome-alerts');
  });
});
