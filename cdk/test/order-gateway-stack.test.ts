import * as cdk from 'aws-cdk-lib';
import { Match, Template } from 'aws-cdk-lib/assertions';
import { Stage } from '@gnome-trading-group/gnome-shared-cdk';
import { OrderGatewayStack } from '../lib/stacks/order-gateway-stack';
import { ORDER_GATEWAYS } from '../lib/config';

describe('the order gateway stack', () => {
  const app = new cdk.App();
  const stack = new OrderGatewayStack(app, 'Gateways', {
    env: { account: '123456789012', region: 'eu-west-1' },
    stage: Stage.DEV,
    registryApiKeyId: 'key-id',
  });
  const template = Template.fromStack(stack);
  const { live, paper } = ORDER_GATEWAYS.modes;

  const taskDefinition = (family: string) => {
    const found = Object.values(template.findResources('AWS::ECS::TaskDefinition', { Properties: { Family: family } }));
    expect(found).toHaveLength(1);
    return (found[0] as any).Properties;
  };
  const roleStatements = (roleName: string) => {
    const roles = template.findResources('AWS::IAM::Role', { Properties: { RoleName: roleName } });
    const [roleId] = Object.keys(roles);
    expect(roleId).toBeDefined();
    const policies = Object.values(template.findResources('AWS::IAM::Policy'))
      .filter((p: any) => p.Properties.Roles.some((r: any) => r.Ref === roleId));
    return JSON.stringify(policies.map((p: any) => p.Properties.PolicyDocument.Statement));
  };

  it('has a live and a paper host group, each scaling from zero on its own instance type', () => {
    template.resourceCountIs('AWS::AutoScaling::AutoScalingGroup', 2);
    template.hasResourceProperties('AWS::ECS::CapacityProvider', Match.objectLike({ Name: live.capacityProvider }));
    template.hasResourceProperties('AWS::ECS::CapacityProvider', Match.objectLike({
      Name: paper.capacityProvider,
      AutoScalingGroupProvider: Match.objectLike({
        ManagedScaling: Match.objectLike({ Status: 'ENABLED', TargetCapacity: 100 }),
        ManagedTerminationProtection: 'ENABLED',
      }),
    }));
    template.hasResourceProperties('AWS::EC2::LaunchTemplate', Match.objectLike({
      LaunchTemplateData: Match.objectLike({ InstanceType: 'c7a.large' }),
    }));
    template.hasResourceProperties('AWS::EC2::LaunchTemplate', Match.objectLike({
      LaunchTemplateData: Match.objectLike({ InstanceType: 'c7a.medium' }),
    }));
    for (const group of Object.values(template.findResources('AWS::AutoScaling::AutoScalingGroup')) as any[]) {
      expect(group.Properties.MinSize).toBe('0');
      expect(group.Properties.NewInstancesProtectedFromScaleIn).toBe(true);
    }
  });

  it('gives the paper base task the same container, smaller, under the paper task role', () => {
    const liveTask = taskDefinition(live.baseFamily);
    const paperTask = taskDefinition(paper.baseFamily);
    const [liveContainer] = liveTask.ContainerDefinitions;
    const [paperContainer] = paperTask.ContainerDefinitions;

    expect(liveContainer.MemoryReservation).toBe(2048);
    expect(liveContainer.LinuxParameters.SharedMemorySize).toBe(512);
    expect(paperContainer.MemoryReservation).toBe(1536);
    expect(paperContainer.LinuxParameters.SharedMemorySize).toBe(384);
    for (const field of ['Image', 'HealthCheck', 'StopTimeout', 'MountPoints', 'Environment', 'LogConfiguration']) {
      expect(paperContainer[field]).toEqual(liveContainer[field]);
    }
    expect(paperTask.Volumes).toEqual(liveTask.Volumes);
    expect(paperTask.ExecutionRoleArn).toEqual(liveTask.ExecutionRoleArn);
    expect(paperTask.TaskRoleArn).not.toEqual(liveTask.TaskRoleArn);
  });

  it('lets a paper gateway read the market-data key, but no trading key or address', () => {
    const paperRole = roleStatements('gnome-order-gateway-paper-task-eu-west-1');
    expect(paperRole).toContain('secret:gnome/api-keys/market-data-*');
    expect(paperRole).toContain('secret:gnomepy/gh-token*');
    expect(paperRole).toContain('apigateway:GET');
    expect(paperRole).toContain('order-gateway#*');
    expect(paperRole).not.toContain('gnome/accounts/');
    expect(paperRole).not.toContain('ec2:');

    const liveRole = roleStatements('gnome-order-gateway-task-eu-west-1');
    expect(liveRole).toContain('gnome/accounts/*/trading-*');
    expect(liveRole).toContain('ec2:AssociateAddress');
    expect(liveRole).not.toContain('market-data');
  });
});
