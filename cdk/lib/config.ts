import { GnomeAccount, Stage } from '@gnome-trading-group/gnome-shared-cdk';

export const GITHUB_REPO = 'gnome-trading-group/gnome-orchestrator';
export const GITHUB_BRANCH = 'release';

export const REGIONS = ['us-east-1', 'ap-northeast-1', 'eu-west-1'];

export interface OrchestratorConfig {
  account: GnomeAccount;
  regions: string[];
  registryApiKeyId: string;
}

export const CONFIGS: { [stage in Stage]?: OrchestratorConfig } = {
  [Stage.DEV]: {
    account: GnomeAccount.InfraDev,
    regions: REGIONS,
    registryApiKeyId: 'rb0pbivke8',
  },
  [Stage.PROD]: {
    account: GnomeAccount.InfraProd,
    regions: REGIONS,
    registryApiKeyId: 'mj0thnxe96',
  },
};
