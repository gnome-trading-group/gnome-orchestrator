import { GnomeAccount, platformRegions, Stage } from '@gnome-trading-group/gnome-shared-cdk';

export const GITHUB_REPO = 'gnome-trading-group/gnome-orchestrator';
export const GITHUB_BRANCH = 'release';

export const REGIONS = platformRegions();

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

/**
 * What order gateways are run by, shared by the gateway stack, the gateway reconciler (given them in its environment)
 * and gateway-start.sh (which finds its address by `addressTag`). gnome-registry's gateways/ensure wakes the reconciler
 * on `reconcilerQueue`, by name.
 */
export const ORDER_GATEWAYS = {
  cluster: 'order-gateways',
  baseFamily: 'order-gateway',
  capacityProvider: 'order-gateway-hosts',
  servicePrefix: 'order-gateway-',
  addressTag: 'gnome:order-gateway',
  reconcilerQueue: 'gateway-reconciler.fifo',
  // One message group, so the reconciler's ticks never overlap.
  reconcilerGroup: 'reconcile',
} as const;
