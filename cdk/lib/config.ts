import { GnomeAccount, Stage } from '@gnome-trading-group/gnome-shared-cdk';

export const GITHUB_REPO = 'gnome-trading-group/gnome-orchestrator';
export const GITHUB_BRANCH = 'release';

export const ECS_REGIONS = ['us-east-1', 'ap-northeast-1'];

export interface OrchestratorConfig {
  account: GnomeAccount;
  ecsRegions: string[];
}

export const CONFIGS: { [stage in Stage]?: OrchestratorConfig } = {
  [Stage.DEV]: {
    account: GnomeAccount.InfraDev,
    ecsRegions: ECS_REGIONS,
  },
  [Stage.PROD]: {
    account: GnomeAccount.InfraProd,
    ecsRegions: ECS_REGIONS,
  },
};
