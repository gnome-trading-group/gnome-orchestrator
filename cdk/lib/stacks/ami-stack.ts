import * as cdk from 'aws-cdk-lib';
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import * as iam from 'aws-cdk-lib/aws-iam';
import * as imagebuilder from 'aws-cdk-lib/aws-imagebuilder';
import * as lambda from 'aws-cdk-lib/aws-lambda';
import * as sns from 'aws-cdk-lib/aws-sns';
import * as cr from 'aws-cdk-lib/custom-resources';
import * as subscriptions from 'aws-cdk-lib/aws-sns-subscriptions';
import * as triggers from 'aws-cdk-lib/triggers';
import { Construct } from 'constructs';
import { createHash } from 'crypto';
import { readFileSync } from 'fs';
import { join } from 'path';

export const AMI_PARAMETER_PREFIX = '/gnome/orchestrator/ami';
// On the Image Builder image whose AMIs new sessions launch from, so retention never deletes it however old it gets.
export const LIVE_IMAGE_TAG = 'gnome:ami-live';
// How long an image that sessions no longer launch from is kept: the window to roll back by repointing its parameter.
const RETAIN_DAYS = 14;
// Canonical's pointer to the current Ubuntu 24.04 image. Ubuntu rather than Amazon Linux because the native
// socket library needs GLIBC_2.38.
const PARENT_IMAGE = 'ssm:/aws/service/canonical/ubuntu/server/24.04/stable/current/amd64/hvm/ebs-gp3/ami-id';
const AMI_DIR = join(__dirname, '..', '..', '..', 'ami');

interface Variant {
  name: string;
  // Only low-latency variants isolate cores; undefined builds the untuned image for the standard profile.
  vcpus?: number;
}

const VARIANTS: Variant[] = [
  { name: '16core', vcpus: 16 },
  { name: '32core', vcpus: 32 },
  { name: '48core', vcpus: 48 },
  { name: 'standard' },
];

/**
 * CPUs isolated for hot threads on a c7i size with two hardware threads per core. AWS numbers sibling threads
 * N and N + cores, so housekeeping takes cores 0 and 1 together with their siblings: OS noise then never shares a
 * physical core with a hot thread. The image tests check that numbering on the real hardware.
 */
export function isolatedCpus(vcpus: number): string {
  const cores = vcpus / 2;
  return `2-${cores - 1},${cores + 2}-${vcpus - 1}`;
}

interface Props extends cdk.StackProps {
  vpc: ec2.IVpc;
  securityGroup: ec2.ISecurityGroup;
  regions: string[];
}

/**
 * Builds the strategy AMIs and publishes each one's id per region to SSM. A build starts on deploy whenever the
 * image's content changed, and only then; the image tests gate publishing, so a failed build never replaces the
 * AMI new sessions launch from. Pipelines can still be started by hand, e.g. to pick up a newer Ubuntu base.
 *
 * Images are deleted, with their AMIs in every region and their snapshots, once they are older than
 * {@link RETAIN_DAYS} and no longer live. Retention by count can't work here: Image Builder counts per recipe
 * version, and every content change is a new version built once.
 */
export class AmiStack extends cdk.Stack {
  constructor(scope: Construct, id: string, props: Props) {
    super(scope, id, props);

    const instanceRole = new iam.Role(this, 'BuildInstanceRole', {
      assumedBy: new iam.ServicePrincipal('ec2.amazonaws.com'),
      managedPolicies: [
        iam.ManagedPolicy.fromAwsManagedPolicyName('AmazonSSMManagedInstanceCore'),
        iam.ManagedPolicy.fromAwsManagedPolicyName('EC2InstanceProfileForImageBuilder'),
      ],
    });
    const instanceProfile = new iam.CfnInstanceProfile(this, 'BuildInstanceProfile', {
      roles: [instanceRole.roleName],
    });

    const completions = new sns.Topic(this, 'BuildCompletions');

    const infrastructure = new imagebuilder.CfnInfrastructureConfiguration(this, 'Infrastructure', {
      name: 'gnome-orchestrator-ami-build',
      instanceProfileName: instanceProfile.ref,
      instanceTypes: ['c7i.large'],
      subnetId: props.vpc.publicSubnets[0].subnetId,
      securityGroupIds: [props.securityGroup.securityGroupId],
      snsTopicArn: completions.topicArn,
      terminateInstanceOnFailure: true,
    });

    const runtime = this.component('runtime', 'Java 17, Python 3.13, CloudWatch agent and the session bootstrap', [
      ['InstallRuntime', readAmiFile('scripts/install-runtime.sh')],
      ['InstallBootstrap', [
        installFile('files/run-strategy.sh', '/opt/gnome/run-strategy.sh', '0755'),
        installFile('files/start-session.sh', '/opt/gnome/start-session.sh', '0755'),
        installFile('files/gnome-strategy.service', '/etc/systemd/system/gnome-strategy.service', '0644'),
        installFile('files/gnome-boot-guard.service', '/etc/systemd/system/gnome-boot-guard.service', '0644'),
        installFile('files/journal-shipper.py', '/opt/gnome/journal-shipper.py', '0644'),
        installFile('files/gnome-journal-shipper.service', '/etc/systemd/system/gnome-journal-shipper.service', '0644'),
        'systemctl daemon-reload',
        // The guard and the shipper start at boot; the session unit is started by user data on the first boot alone.
        'systemctl enable gnome-boot-guard.service',
        'systemctl enable gnome-journal-shipper.service',
      ].join('\n')],
    ], [
      ['CheckRuntime', [
        'set -euxo pipefail',
        '/opt/gnome/venv/bin/python -c "import sys; assert sys.version_info[:2] == (3, 13), sys.version"',
        'java -version',
        'test -x /usr/lib/jvm/current/bin/java',
        // LibraryLoader extracts native libraries to /tmp and loads them from there.
        "! findmnt -no OPTIONS /tmp | grep -q noexec",
        'systemctl is-enabled gnome-boot-guard.service',
        'systemctl is-enabled gnome-journal-shipper.service',
        // The shipper's tests, on the image's own Python and libraries; nothing else in CI runs Python.
        'install -d /tmp/shipper-test && cp /opt/gnome/journal-shipper.py /tmp/shipper-test/',
        installFile('files/test_journal_shipper.py', '/tmp/shipper-test/test_journal_shipper.py', '0644'),
        '/opt/gnome/shipper-venv/bin/python -m unittest discover -s /tmp/shipper-test -p "test_*.py"',
        'rm -rf /tmp/shipper-test',
        // No [Install] section, so systemd reports it "static" (exit 0): it can only be started, by user data.
        'test "$(systemctl is-enabled gnome-strategy.service || true)" != enabled',
        // Nothing may upgrade packages, or restart services, under a running session.
        'test "$(systemctl is-enabled apt-daily-upgrade.timer || true)" = masked',
        'test "$(systemctl is-enabled apt-daily.timer || true)" = masked',
        '! dpkg -s unattended-upgrades >/dev/null 2>&1',
        "grep -q \"restart} = 'l'\" /etc/needrestart/conf.d/90-gnome-no-restart.conf",
      ].join('\n')],
    ]);

    const distribution = new imagebuilder.CfnDistributionConfiguration(this, 'Distribution', {
      name: 'gnome-orchestrator-ami',
      distributions: props.regions.map(region => ({
        region,
        amiDistributionConfiguration: {
          name: 'gnome-orchestrator-{{ imagebuilder:buildDate }}',
          amiTags: { 'gnome:purpose': 'orchestrator-ami' },
        },
      })),
    });

    for (const variant of VARIANTS) {
      const components = [{ componentArn: runtime.attrArn }];
      const componentVersions = [runtime.version];
      if (variant.vcpus) {
        const isolated = isolatedCpus(variant.vcpus);
        const tuning = this.component(`kernel-tuning-${variant.name}`, `Isolate CPUs ${isolated}`, [
          ['TuneKernel', `ISOLATED='${isolated}'\n${readAmiFile('scripts/tune-kernel.sh')}`],
          ['InstallGovernor', [
            installFile('files/gnome-cpu-governor.service', '/etc/systemd/system/gnome-cpu-governor.service', '0644'),
            'systemctl daemon-reload',
            'systemctl enable gnome-cpu-governor.service',
          ].join('\n')],
        ], [
          ['CheckIsolation', checkIsolationScript(isolated)],
        ]);
        components.push({ componentArn: tuning.attrArn });
        componentVersions.push(tuning.version);
      }

      const recipeName = `gnome-orchestrator-${variant.name}`;
      const recipe = new imagebuilder.CfnImageRecipe(this, `Recipe-${variant.name}`, {
        name: recipeName,
        // Recipes are immutable per version too, so any component change must bump it.
        version: contentVersion(`${variant.name}|${componentVersions.join('|')}`),
        parentImage: PARENT_IMAGE,
        components,
        blockDeviceMappings: [{
          deviceName: '/dev/sda1',
          ebs: { volumeSize: 30, volumeType: 'gp3', deleteOnTermination: true },
        }],
      });

      const pipeline = new imagebuilder.CfnImagePipeline(this, `Pipeline-${variant.name}`, {
        name: recipeName,
        imageRecipeArn: recipe.attrArn,
        infrastructureConfigurationArn: infrastructure.attrArn,
        distributionConfigurationArn: distribution.attrArn,
        imageTestsConfiguration: { imageTestsEnabled: true, timeoutMinutes: 60 },
        status: 'ENABLED',
      });

      // The recipe version is a hash of everything baked into the image, so keying the build on it starts one
      // exactly when a deploy changes the image. Starting is asynchronous; the deploy does not wait for the build.
      const buildId = `${variant.name}-${recipe.version}`;
      const startBuild: cr.AwsSdkCall = {
        service: 'imagebuilder',
        action: 'StartImagePipelineExecution',
        parameters: { imagePipelineArn: pipeline.attrArn, clientToken: buildId },
        physicalResourceId: cr.PhysicalResourceId.of(buildId),
      };
      new cr.AwsCustomResource(this, `BuildOnChange-${variant.name}`, {
        onCreate: startBuild,
        onUpdate: startBuild,
        policy: cr.AwsCustomResourcePolicy.fromStatements([
          new iam.PolicyStatement({
            actions: ['imagebuilder:StartImagePipelineExecution'],
            resources: [pipeline.attrArn],
          }),
        ]),
        installLatestAwsSdk: false,
      });
    }

    // CDK 2.176's Image Builder L1 cannot write SSM parameters per distribution region, so a build-completion
    // hook does it: the launcher looks each AMI up in the region it launches into.
    // It also moves the live tag onto the image it just published, from the parameters themselves.
    const publisher = new lambda.Function(this, 'AmiPublisher', {
      runtime: lambda.Runtime.NODEJS_20_X,
      handler: 'index.handler',
      timeout: cdk.Duration.minutes(2),
      environment: {
        PARAMETER_PREFIX: AMI_PARAMETER_PREFIX,
        LIVE_IMAGE_TAG,
        VARIANTS: JSON.stringify(VARIANTS.map(v => v.name)),
      },
      code: lambda.Code.fromInline(AMI_PUBLISHER_SOURCE),
    });
    publisher.addToRolePolicy(new iam.PolicyStatement({
      actions: ['ssm:PutParameter', 'ssm:GetParameter'],
      resources: [`arn:aws:ssm:*:${this.account}:parameter${AMI_PARAMETER_PREFIX}/*`],
    }));
    publisher.addToRolePolicy(new iam.PolicyStatement({
      actions: ['imagebuilder:ListImages', 'imagebuilder:ListImageBuildVersions'],
      resources: ['*'],
    }));
    publisher.addToRolePolicy(new iam.PolicyStatement({
      actions: ['imagebuilder:TagResource', 'imagebuilder:UntagResource'],
      resources: [`arn:aws:imagebuilder:${this.region}:${this.account}:image/gnome-orchestrator-*`],
    }));
    completions.addSubscription(new subscriptions.LambdaSubscription(publisher));

    const retention = new imagebuilder.CfnLifecyclePolicy(this, 'Retention', {
      name: 'gnome-orchestrator-ami-retention',
      description: `Deletes images older than ${RETAIN_DAYS} days, with their AMIs and snapshots, unless live`,
      executionRole: new iam.Role(this, 'RetentionRole', {
        assumedBy: new iam.ServicePrincipal('imagebuilder.amazonaws.com'),
        managedPolicies: [
          iam.ManagedPolicy.fromAwsManagedPolicyName('service-role/EC2ImageBuilderLifecycleExecutionPolicy'),
        ],
      }).roleArn,
      resourceType: 'AMI_IMAGE',
      resourceSelection: {
        recipes: VARIANTS.map(v => ({ name: `gnome-orchestrator-${v.name}`, semanticVersion: 'x.x.x' })),
      },
      policyDetails: [{
        action: { type: 'DELETE', includeResources: { amis: true, snapshots: true } },
        filter: { type: 'AGE', value: RETAIN_DAYS, unit: 'DAYS' },
        // On the Image Builder image, so the image and its AMIs in every region are skipped together.
        exclusionRules: { tagMap: { [LIVE_IMAGE_TAG]: 'true' } },
      }],
      status: 'ENABLED',
    });

    // The live images must be tagged before the policy first runs, or any older than the window would go with the
    // rest; until a build next completes, only this run tags them.
    new triggers.Trigger(this, 'TagLiveImages', {
      handler: publisher,
      executeBefore: [retention],
      executeOnHandlerChange: true,
    });
  }

  private component(
    name: string,
    description: string,
    build: [string, string][],
    test: [string, string][],
  ): imagebuilder.CfnComponent {
    const data = componentDocument(`gnome-${name}`, description, build, test);
    return new imagebuilder.CfnComponent(this, `Component-${name}`, {
      name: `gnome-orchestrator-${name}`,
      // Components are immutable per version, so the version tracks the content.
      version: contentVersion(data),
      platform: 'Linux',
      data,
    });
  }
}

function readAmiFile(relative: string): string {
  return readFileSync(join(AMI_DIR, relative), 'utf8');
}

function installFile(source: string, destination: string, mode: string): string {
  const content = Buffer.from(readAmiFile(source)).toString('base64');
  return `echo '${content}' | base64 -d > ${destination}\nchmod ${mode} ${destination}`;
}

// Runs on the small build-size test instance, where most of the range does not exist: the kernel isolates only the
// CPUs it has, so this checks the command line and that intersection. Sibling layout is checked at session boot.
function checkIsolationScript(isolated: string): string {
  return [
    'set -euxo pipefail',
    'cat /proc/cmdline',
    `grep -qw "isolcpus=${isolated}" /proc/cmdline`,
    `grep -qw "nohz_full=${isolated}" /proc/cmdline`,
    'python3 - <<\'PY\'',
    'from pathlib import Path',
    'def cpus(spec):',
    '    out = set()',
    '    for part in filter(None, spec.strip().split(",")):',
    '        lo, _, hi = part.partition("-")',
    '        out.update(range(int(lo), int(hi or lo) + 1))',
    '    return out',
    'base = Path("/sys/devices/system/cpu")',
    `expected = cpus("${isolated}") & cpus((base / "possible").read_text())`,
    'actual = cpus((base / "isolated").read_text())',
    'assert actual == expected, f"isolated {sorted(actual)}, expected {sorted(expected)}"',
    'PY',
  ].join('\n');
}

function indent(text: string, spaces: number): string {
  return text.split('\n').map(line => (line.length ? ' '.repeat(spaces) + line : line)).join('\n');
}

function componentDocument(name: string, description: string, build: [string, string][], test: [string, string][]): string {
  const phase = (phaseName: string, steps: [string, string][]) => [
    `  - name: ${phaseName}`,
    '    steps:',
    ...steps.map(([stepName, script]) => [
      `      - name: ${stepName}`,
      '        action: ExecuteBash',
      '        inputs:',
      '          commands:',
      '            - |',
      indent(script.trimEnd(), 14),
    ].join('\n')),
  ].join('\n');
  return [
    `name: ${name}`,
    `description: ${description}`,
    'schemaVersion: 1.0',
    'phases:',
    phase('build', build),
    ...(test.length ? [phase('test', test)] : []),
    '',
  ].join('\n');
}

function contentVersion(content: string): string {
  const hash = createHash('sha256').update(content).digest();
  return `1.${hash.readUInt16BE(0)}.${hash.readUInt16BE(2)}`;
}

const AMI_PUBLISHER_SOURCE = `
const { SSMClient, PutParameterCommand, GetParameterCommand } = require('@aws-sdk/client-ssm');
const {
  ImagebuilderClient, ListImagesCommand, ListImageBuildVersionsCommand, TagResourceCommand, UntagResourceCommand,
} = require('@aws-sdk/client-imagebuilder');

const PREFIX = process.env.PARAMETER_PREFIX;
const LIVE_TAG = process.env.LIVE_IMAGE_TAG;
const imagebuilder = new ImagebuilderClient({});

async function publish(image) {
  if (image.state?.status !== 'AVAILABLE') return;
  const variant = (image.name || '').replace(/^gnome-orchestrator-/, '');
  for (const ami of image.outputResources?.amis ?? []) {
    const name = PREFIX + '/' + variant;
    await new SSMClient({ region: ami.region }).send(new PutParameterCommand({
      Name: name,
      Value: ami.image,
      Type: 'String',
      Overwrite: true,
    }));
    console.log('Published', name, '=', ami.image, 'in', ami.region);
  }
}

async function builds(recipeName) {
  const out = [];
  let versionsToken;
  do {
    const versions = await imagebuilder.send(new ListImagesCommand({
      owner: 'Self', filters: [{ name: 'name', values: [recipeName] }], nextToken: versionsToken,
    }));
    for (const version of versions.imageVersionList ?? []) {
      let buildsToken;
      do {
        const page = await imagebuilder.send(new ListImageBuildVersionsCommand({
          imageVersionArn: version.arn, nextToken: buildsToken,
        }));
        out.push(...(page.imageSummaryList ?? []));
        buildsToken = page.nextToken;
      } while (buildsToken);
    }
    versionsToken = versions.nextToken;
  } while (versionsToken);
  return out;
}

// The parameters decide what's live, so a parameter repointed by hand to roll back is honoured too. Tags before
// untagging, so no moment leaves a variant with nothing protected.
async function tagLiveImages() {
  const ssm = new SSMClient({});
  for (const variant of JSON.parse(process.env.VARIANTS)) {
    let live;
    try {
      live = (await ssm.send(new GetParameterCommand({ Name: PREFIX + '/' + variant }))).Parameter.Value;
    } catch (e) {
      if (e.name === 'ParameterNotFound') continue;
      throw e;
    }
    const images = await builds('gnome-orchestrator-' + variant);
    const isLive = i => (i.outputResources?.amis ?? []).some(a => a.region === process.env.AWS_REGION && a.image === live);
    const liveImages = images.filter(isLive);
    if (liveImages.length === 0) {
      // Untagging the others now could let retention delete the AMI sessions launch from.
      throw new Error('No image found for ' + variant + "'s live AMI " + live);
    }
    for (const image of liveImages) {
      if (image.tags?.[LIVE_TAG] !== 'true') {
        await imagebuilder.send(new TagResourceCommand({ resourceArn: image.arn, tags: { [LIVE_TAG]: 'true' } }));
        console.log('Tagged live', image.arn);
      }
    }
    for (const image of images) {
      if (!isLive(image) && image.tags?.[LIVE_TAG] !== undefined) {
        await imagebuilder.send(new UntagResourceCommand({ resourceArn: image.arn, tagKeys: [LIVE_TAG] }));
        console.log('Untagged', image.arn);
      }
    }
  }
}

// Called with build completions from SNS, and with no records by the deploy-time trigger.
exports.handler = async (event) => {
  for (const record of event?.Records ?? []) {
    await publish(JSON.parse(record.Sns.Message));
  }
  await tagLiveImages();
};
`;
