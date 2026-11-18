import * as cdk from 'aws-cdk-lib';
import { Annotations, Match, Template } from 'aws-cdk-lib/assertions';
import { Stage } from '@gnome-trading-group/gnome-shared-cdk';
import { OrderGatewayStack } from '../lib/stacks/order-gateway-stack';
import { ORDER_GATEWAYS, zoneCapacityProvider } from '../lib/config';

// The platform network's lookup as the account would answer it: a public subnet in each of eu-west-1's platform zones.
const PLATFORM_CONTEXT = {
  'vpc-provider:account=123456789012:filter.tag:gnome:network=platform:region=eu-west-1:returnAsymmetricSubnets=true': {
    vpcId: 'vpc-0platform', vpcCidrBlock: '10.144.0.0/16', ownerAccountId: '123456789012', availabilityZones: [],
    subnetGroups: [{ name: 'public', type: 'Public', subnets: [
      { subnetId: 'subnet-a', cidr: '10.144.0.0/20', availabilityZone: 'eu-west-1a', routeTableId: 'rtb-a' },
      { subnetId: 'subnet-b', cidr: '10.144.16.0/20', availabilityZone: 'eu-west-1b', routeTableId: 'rtb-b' },
    ] }],
  },
  'security-group:account=123456789012:region=eu-west-1:securityGroupName=gnome-platform-sg:vpcId=vpc-0platform': {
    securityGroupId: 'sg-0platform', allowAllOutbound: false,
  },
};

describe('the order gateway stack', () => {
  const app = new cdk.App({ context: PLATFORM_CONTEXT });
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
    template.resourceCountIs('AWS::AutoScaling::AutoScalingGroup', 6);
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

  it('has a live and a paper host group in each platform zone, held to that zone, named by its zone ID', () => {
    const groups = template.findResources('AWS::AutoScaling::AutoScalingGroup');
    const providers = Object.values(template.findResources('AWS::ECS::CapacityProvider')) as any[];
    for (const [zoneId, subnet] of [['euw1-az2', 'subnet-a'], ['euw1-az3', 'subnet-b']]) {
      for (const regional of [live.capacityProvider, paper.capacityProvider]) {
        const provider = providers.find((p) => p.Properties.Name === zoneCapacityProvider(regional, zoneId));
        expect(provider).toBeDefined();
        const group = groups[provider.Properties.AutoScalingGroupProvider.AutoScalingGroupArn.Ref];
        expect(group.Properties.VPCZoneIdentifier).toEqual([subnet]);
        expect(group.Properties.MinSize).toBe('0');
      }
    }
    const regionalGroup = groups[providers.find((p) => p.Properties.Name === live.capacityProvider)
      .Properties.AutoScalingGroupProvider.AutoScalingGroupArn.Ref];
    expect(regionalGroup.Properties.VPCZoneIdentifier).toEqual(['subnet-a', 'subnet-b']);
  });

  it('tries the next instance type in the same zone: four live, three paper', () => {
    const overrides = (Object.values(template.findResources('AWS::AutoScaling::AutoScalingGroup')) as any[])
      .map((g) => g.Properties.MixedInstancesPolicy?.LaunchTemplate.Overrides?.map((o: any) => o.InstanceType))
      .filter(Boolean);
    expect(overrides).toContainEqual(['c7a.large', 'c7i.large', 'm7a.large', 'm7i.large']);
    expect(overrides).toContainEqual(['c7a.medium', 'm7a.medium', 'c7i.large']);
    const zoneLive = (Object.values(template.findResources('AWS::EC2::LaunchTemplate')) as any[])
      .filter((t) => t.Properties.LaunchTemplateData.Placement);
    expect(zoneLive).toHaveLength(3);
  });

  it('reserves the health port on its host, so ECS never places two gateways on one host', () => {
    for (const family of [live.baseFamily, paper.baseFamily]) {
      expect(taskDefinition(family).ContainerDefinitions[0].PortMappings)
        .toEqual([{ ContainerPort: 8080, HostPort: 8080, Protocol: 'tcp' }]);
    }
  });

  it('refuses a zone the platform network has no subnet in', () => {
    const app = new cdk.App({ context: {
      ...PLATFORM_CONTEXT,
      'vpc-provider:account=123456789012:filter.tag:gnome:network=platform:region=eu-west-1:returnAsymmetricSubnets=true': {
        ...(PLATFORM_CONTEXT as any)['vpc-provider:account=123456789012:filter.tag:gnome:network=platform:region=eu-west-1:returnAsymmetricSubnets=true'],
        subnetGroups: [{ name: 'public', type: 'Public', subnets: [
          { subnetId: 'subnet-a', cidr: '10.144.0.0/20', availabilityZone: 'eu-west-1a', routeTableId: 'rtb-a' },
        ] }],
      },
    } });
    const stack = new OrderGatewayStack(app, 'Gateways', {
      env: { account: '123456789012', region: 'eu-west-1' }, stage: Stage.DEV, registryApiKeyId: 'key-id',
    });
    Annotations.fromStack(stack).hasError('*', Match.stringLikeRegexp('no public subnet in eu-west-1b'));
  });

  it('still builds against the placeholder network of an uncached lookup, so the lookup can run', () => {
    const uncached = new cdk.App();
    expect(() => new OrderGatewayStack(uncached, 'Gateways', {
      env: { account: '123456789012', region: 'us-east-2' }, stage: Stage.DEV, registryApiKeyId: 'key-id',
    })).not.toThrow();
  });

  it('launches live hosts into the precision-time group, c7a first and c7i when c7a can\'t launch', () => {
    const templates = Object.values(template.findResources('AWS::EC2::LaunchTemplate')) as any[];
    const byType = (type: string) => templates.find((t) => t.Properties.LaunchTemplateData.InstanceType === type);
    expect(byType('c7a.large').Properties.LaunchTemplateData.Placement.GroupName).toEqual(
      expect.objectContaining({ Ref: expect.stringMatching(/precisiontimeplacementgroup/i) }));
    expect(byType('c7a.medium').Properties.LaunchTemplateData.Placement).toBeUndefined();
    template.hasResourceProperties('AWS::AutoScaling::AutoScalingGroup', Match.objectLike({
      MixedInstancesPolicy: Match.objectLike({
        LaunchTemplate: Match.objectLike({
          Overrides: [{ InstanceType: 'c7a.large' }, { InstanceType: 'c7i.large' }],
        }),
        InstancesDistribution: Match.objectLike({
          OnDemandAllocationStrategy: 'prioritized',
          OnDemandPercentageAboveBaseCapacity: 100,
        }),
      }),
    }));
  });

  it('sets up and reports the clock on every host, and shows its state to the gateway read-only', () => {
    for (const lt of Object.values(template.findResources('AWS::EC2::LaunchTemplate')) as any[]) {
      expect(JSON.stringify(lt.Properties.LaunchTemplateData.UserData)).toContain('gnome-clock-setup.service');
    }
    for (const family of [live.baseFamily, paper.baseFamily]) {
      const task = taskDefinition(family);
      expect(task.Volumes).toContainEqual({ Name: 'clock', Host: { SourcePath: '/run/gnome' } });
      expect(task.ContainerDefinitions[0].MountPoints).toContainEqual(
        { SourceVolume: 'clock', ContainerPath: '/run/gnome', ReadOnly: true });
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

  it('lets a live gateway read its roll item, to hand over when the reconciler rolls it', () => {
    const liveRole = JSON.parse(roleStatements('gnome-order-gateway-task-eu-west-1')).flat();
    expect(liveRole).toContainEqual(expect.objectContaining({
      Action: 'dynamodb:GetItem',
      Condition: { 'ForAllValues:StringLike': { 'dynamodb:LeadingKeys': ['order-gateway#*#roll'] } },
    }));
  });
});
