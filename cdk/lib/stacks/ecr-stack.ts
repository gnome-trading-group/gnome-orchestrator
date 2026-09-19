import * as cdk from 'aws-cdk-lib';
import * as ecr from 'aws-cdk-lib/aws-ecr';
import * as iam from 'aws-cdk-lib/aws-iam';
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

    // OIDC provider is created once per account in gnome-controller's backtest-stack.
    // Import it here rather than creating a second one (which would fail).
    const githubOidc = iam.OpenIdConnectProvider.fromOpenIdConnectProviderArn(
      this, 'GithubActionsOidcProvider',
      `arn:aws:iam::${this.account}:oidc-provider/token.actions.githubusercontent.com`,
    );

    const githubEcrRole = new iam.Role(this, 'GithubActionsEcrRole', {
      assumedBy: new iam.WebIdentityPrincipal(githubOidc.openIdConnectProviderArn, {
        StringEquals: {
          'token.actions.githubusercontent.com:aud': 'sts.amazonaws.com',
          'token.actions.githubusercontent.com:sub':
            'repo:gnome-trading-group/gnome-orchestrator:ref:refs/heads/main',
        },
      }),
      description: 'Assumed by GitHub Actions gnome-orchestrator CI to push image to ECR',
    });

    repository.grantPush(githubEcrRole);

    new cdk.CfnOutput(this, 'RepositoryUri', {
      value: repository.repositoryUri,
      exportName: 'OrchestratorRepositoryUri',
    });

    new cdk.CfnOutput(this, 'GithubActionsEcrRoleArn', {
      value: githubEcrRole.roleArn,
      description: 'Set as AWS_ECR_ROLE_ARN_DEV / AWS_ECR_ROLE_ARN_PROD secret in gnome-orchestrator repo',
    });
  }
}
