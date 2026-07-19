import * as cdk from 'aws-cdk-lib';
import * as ecr from 'aws-cdk-lib/aws-ecr';
import { Construct } from 'constructs';

interface Props extends cdk.StackProps {
  ecsRegions: string[];
}

export class EcrStack extends cdk.Stack {
  public readonly repositoryName = 'gnome-orchestrator';

  constructor(scope: Construct, id: string, props: Props) {
    super(scope, id, props);

    const repository = new ecr.Repository(this, 'OrchestratorRepository', {
      repositoryName: this.repositoryName,
      lifecycleRules: [
        { maxImageCount: 10, description: 'Keep last 10 images' },
      ],
    });

    const replicationRegions = props.ecsRegions.filter(r => r !== 'us-east-1');
    if (replicationRegions.length > 0) {
      new ecr.CfnReplicationConfiguration(this, 'ReplicationConfig', {
        replicationConfiguration: {
          rules: [
            {
              destinations: replicationRegions.map(region => ({
                region,
                registryId: this.account,
              })),
              repositoryFilters: [
                {
                  filter: this.repositoryName,
                  filterType: 'PREFIX_MATCH',
                },
              ],
            },
          ],
        },
      });
    }

    new cdk.CfnOutput(this, 'RepositoryUri', {
      value: repository.repositoryUri,
      exportName: 'OrchestratorRepositoryUri',
    });
  }
}
