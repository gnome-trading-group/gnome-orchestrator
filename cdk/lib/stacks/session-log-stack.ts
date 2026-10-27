import * as cdk from 'aws-cdk-lib';
import * as logs from 'aws-cdk-lib/aws-logs';
import { Construct } from 'constructs';

/**
 * Where strategy instances in this region log. Kept under its original construct id (OrchestratorEcsStack-<region>):
 * the stack once held the ECS cluster and then the sessions' own network, and a new id would recreate the log group.
 */
export class SessionLogStack extends cdk.Stack {
  public readonly logGroup: logs.LogGroup;

  constructor(scope: Construct, id: string, props: cdk.StackProps) {
    super(scope, id, props);

    this.logGroup = new logs.LogGroup(this, 'OrchestratorLogGroup', {
      logGroupName: `/gnome/orchestrator/${this.region}`,
      retention: logs.RetentionDays.ONE_MONTH,
    });
  }
}
