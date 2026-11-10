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
