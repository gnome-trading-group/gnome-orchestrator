import * as cdk from 'aws-cdk-lib';
import * as iam from 'aws-cdk-lib/aws-iam';

// gnome-pipelines' DataStack owns the bucket and these parameters; gnomepy.data reads the parameters to find it.
const TABLE_BUCKET = "gnome-research-tables";
// Like the bucket, the parameters exist only here, whatever region the grantee runs in.
const TABLE_REGION = "us-east-1";
const PARAMETER_PREFIX = "/gnome/research/";

const READ_ACTIONS = [
  "s3tables:GetTableBucket",
  "s3tables:GetNamespace",
  "s3tables:ListNamespaces",
  "s3tables:ListTables",
  "s3tables:GetTable",
  "s3tables:GetTableMetadataLocation",
  "s3tables:GetTableData",
];
const WRITE_ACTIONS = [
  "s3tables:CreateNamespace",
  "s3tables:CreateTable",
  "s3tables:UpdateTableMetadataLocation",
  "s3tables:PutTableData",
];

/** Lets a role read (or also write) the research datasets and artifact index through gnomepy.data. */
export function grantResearchTables(stack: cdk.Stack, grantee: iam.IGrantable, opts: { write: boolean }): void {
  const bucketArn = `arn:aws:s3tables:${TABLE_REGION}:${stack.account}:bucket/${TABLE_BUCKET}`;
  grantee.grantPrincipal.addToPrincipalPolicy(new iam.PolicyStatement({
    actions: opts.write ? [...READ_ACTIONS, ...WRITE_ACTIONS] : READ_ACTIONS,
    resources: [bucketArn, `${bucketArn}/table/*`],
  }));
  grantee.grantPrincipal.addToPrincipalPolicy(new iam.PolicyStatement({
    actions: ["ssm:GetParameters"],
    resources: [`arn:aws:ssm:${TABLE_REGION}:${stack.account}:parameter${PARAMETER_PREFIX}*`],
  }));
}
