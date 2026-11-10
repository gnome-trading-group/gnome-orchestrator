import * as cdk from 'aws-cdk-lib';
import * as iam from 'aws-cdk-lib/aws-iam';
import * as lambda from 'aws-cdk-lib/aws-lambda';
import * as destinations from 'aws-cdk-lib/aws-lambda-destinations';
import * as sqs from 'aws-cdk-lib/aws-sqs';
import * as logs from 'aws-cdk-lib/aws-logs';
import { Construct } from 'constructs';
import { PRIMARY_REGION, Stage } from '@gnome-trading-group/gnome-shared-cdk';
import { join } from 'path';

/** The function's name in every region it runs in, for the runbook's `aws lambda invoke` and the registry's route. */
export const EMERGENCY_CANCEL_FUNCTION = 'EmergencyCancel';

// What each stage trades against: dev is Kalshi's demo exchange, as dev sessions are.
const VENUE_URLS: Partial<Record<Stage, Record<string, string>>> = {
  [Stage.DEV]: {
    KALSHI_API_URL: 'https://external-api.demo.kalshi.co',
    POLYMARKET_US_API_URL: 'https://api.polymarket.us',
    POLYMARKET_INTL_CLOB_URL: 'https://clob.polymarket.com',
  },
  [Stage.PROD]: {
    KALSHI_API_URL: 'https://external-api.kalshi.com',
    POLYMARKET_US_API_URL: 'https://api.polymarket.us',
    POLYMARKET_INTL_CLOB_URL: 'https://clob.polymarket.com',
  },
};

interface Props extends cdk.StackProps {
  stage: Stage;
}

/**
 * The break-glass for a broken order gateway: cancels every open order on one venue account straight on the venue,
 * with nothing of the gateway's (lambda/emergency_cancel). One in every platform region, like the orchestrator itself;
 * an account is cancelled from its exchange's region (the registry's sm.exchange.region), where its gateway runs.
 * It reads only trading secrets, is never retried on its own, and puts each call on the alert bus.
 */
export class EmergencyCancelStack extends cdk.Stack {
  constructor(scope: Construct, id: string, props: Props) {
    super(scope, id, props);

    const venueUrls = VENUE_URLS[props.stage];
    if (!venueUrls) {
      throw new Error(`No venue URLs for stage ${props.stage}`);
    }
    const source = join(__dirname, '..', '..', 'lambda', 'emergency_cancel');
    const fn = new lambda.Function(this, 'EmergencyCancel', {
      functionName: EMERGENCY_CANCEL_FUNCTION,
      description: 'Cancels every open order on one venue account without the order gateway',
      runtime: lambda.Runtime.PYTHON_3_12,
      architecture: lambda.Architecture.X86_64,
      handler: 'handler.handler',
      code: lambda.Code.fromAsset(source, {
        exclude: ['tests', '__pycache__', '.pytest_cache'],
        bundling: {
          image: lambda.Runtime.PYTHON_3_12.bundlingImage,
          platform: 'linux/amd64',
          command: [
            'bash', '-c',
            // Wheels for the Lambda's own platform, whatever machine synths this.
            'pip install -r requirements.txt -t /asset-output --only-binary=:all: --platform manylinux2014_x86_64 '
              + '--python-version 3.12 --implementation cp && cp handler.py venues.py /asset-output/',
          ],
        },
      }),
      // A few thousand orders cancelled one by one, waiting out the venue's rate limit where it says to.
      timeout: cdk.Duration.minutes(5),
      memorySize: 256,
      retryAttempts: 0,
      // An async call throttled by the account's concurrency would otherwise wait up to six hours and then cancel
      // everything with nobody watching: one not started within a minute is dropped, onto the failure queue.
      maxEventAge: cdk.Duration.seconds(60),
      onFailure: new destinations.SqsDestination(new sqs.Queue(this, 'EmergencyCancelFailures', {
        retentionPeriod: cdk.Duration.days(14),
        enforceSSL: true,
      })),
      environment: venueUrls,
      logGroup: new logs.LogGroup(this, 'EmergencyCancelLogs', {
        logGroupName: `/aws/lambda/${EMERGENCY_CANCEL_FUNCTION}`,
        retention: logs.RetentionDays.ONE_YEAR,
        removalPolicy: cdk.RemovalPolicy.RETAIN,
      }),
    });

    // Trading secrets are replicated to every region, so each function reads its own region's copy.
    fn.addToRolePolicy(new iam.PolicyStatement({
      actions: ['secretsmanager:GetSecretValue'],
      resources: [`arn:aws:secretsmanager:${this.region}:${this.account}:secret:gnome/accounts/*/trading-*`],
    }));
    fn.addToRolePolicy(new iam.PolicyStatement({
      actions: ['events:PutEvents'],
      resources: [`arn:aws:events:${PRIMARY_REGION}:${this.account}:event-bus/gnome-alerts`],
    }));
  }
}
