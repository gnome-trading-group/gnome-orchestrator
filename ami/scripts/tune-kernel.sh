set -euxo pipefail
export DEBIAN_FRONTEND=noninteractive

# Runs last among the cloud image's grub.d snippets, so it appends to their command line.
cat > /etc/default/grub.d/99-gnome-isolation.cfg <<GRUB
GRUB_CMDLINE_LINUX_DEFAULT="\$GRUB_CMDLINE_LINUX_DEFAULT isolcpus=${ISOLATED} nohz_full=${ISOLATED} rcu_nocbs=${ISOLATED} nosoftlockup tsc=reliable transparent_hugepage=never intel_pstate=disable processor.max_cstate=1"
GRUB
update-grub

apt-get install -y irqbalance linux-tools-common "linux-tools-$(uname -r)" || apt-get install -y irqbalance linux-tools-common
echo "IRQBALANCE_BANNED_CPULIST=${ISOLATED}" >> /etc/default/irqbalance

echo 'vm.swappiness=0' > /etc/sysctl.d/99-gnome.conf
