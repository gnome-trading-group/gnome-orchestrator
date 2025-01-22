#!/usr/bin/env node
import * as cdk from 'aws-cdk-lib';
import { OrchestratorPipelineStack } from '../lib/orchestrator-pipeline-stack';
import * as config from '../lib/config';

const app = new cdk.App();
new OrchestratorPipelineStack(app, 'OrchestratorPipelineStack', {
  env: config.ACCOUNTS.pipelines,
});
app.synth();
