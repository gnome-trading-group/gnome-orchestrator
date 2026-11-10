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
