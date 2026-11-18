// Shared by the gateway stack (config.ts) and the gateway reconciler's Lambda, which must not bundle the CDK: no imports.

/** The capacity provider of a mode's hosts in one zone, by zone ID (the same name in every account). */
export function zoneCapacityProvider(regional: string, zoneId: string): string {
  return `${regional}-${zoneId}`;
}
