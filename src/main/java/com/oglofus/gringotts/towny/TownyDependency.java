package com.oglofus.gringotts.towny;

import static com.oglofus.gringotts.towny.TownyConfiguration.CONF;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Tag;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.plugin.Plugin;
import org.gestern.gringotts.AccountChest;
import org.gestern.gringotts.AccountInventory;
import org.gestern.gringotts.Gringotts;
import org.gestern.gringotts.GringottsAccount;
import org.gestern.gringotts.Permissions;
import org.gestern.gringotts.Util;
import org.gestern.gringotts.accountholder.AccountHolder;
import org.gestern.gringotts.api.dependency.Dependency;
import org.gestern.gringotts.event.PlayerVaultCreationEvent;

import com.oglofus.gringotts.towny.nation.NationAccountHolder;
import com.oglofus.gringotts.towny.nation.NationHolderProvider;
import com.oglofus.gringotts.towny.town.TownAccountHolder;
import com.oglofus.gringotts.towny.town.TownHolderProvider;
import com.palmergames.bukkit.towny.Towny;
import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.TownyUniverse;
import com.palmergames.bukkit.towny.exceptions.NotRegisteredException;
import com.palmergames.bukkit.towny.object.Nation;
import com.palmergames.bukkit.towny.object.Resident;
import com.palmergames.bukkit.towny.object.Town;
import com.palmergames.bukkit.towny.object.metadata.IntegerDataField;

/**
 * The type Towny dependency.
 */
public class TownyDependency implements Dependency, Listener {
    private IntegerDataField defaultVaultCountField = new IntegerDataField("vault_count", 1);
    private final NationHolderProvider nationHolderProvider;
    private final TownHolderProvider   townHolderProvider;
    private final Gringotts            gringotts;
    private final Towny                plugin;
    private final String               id;

    /**
     * Instantiates a new Towny dependency.
     *
     * @param gringotts the gringotts
     * @param plugin    the plugin
     */
    public TownyDependency(Gringotts gringotts, Plugin plugin) {
        if (plugin == null) {
            throw new NullPointerException("'plugin' is null");
        }

        if (!(plugin instanceof Towny)) {
            throw new IllegalArgumentException(
                    "The 'plugin' needs to be an instance of com.palmergames.bukkit.towny.Towny");
        }

        this.gringotts = gringotts;
        this.plugin    = (Towny) plugin;
        this.id        = "towny";

        this.nationHolderProvider = new NationHolderProvider();
        this.townHolderProvider   = new TownHolderProvider();
    }

    /**
     * Gets id.
     *
     * @return the id
     */
    @Override
    public String getId() {
        return id;
    }

    /**
     * Gets plugin.
     *
     * @return the plugin
     */
    @Override
    public Plugin getPlugin() {
        return this.plugin;
    }

    /**
     * On enable.
     */
    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this.gringotts);
        Bukkit.getPluginManager().registerEvents(this.townHolderProvider, this.gringotts);
        Bukkit.getPluginManager().registerEvents(this.nationHolderProvider, this.gringotts);

        Gringotts.instance.getAccountHolderFactory().registerAccountHolderProvider(TownAccountHolder.ACCOUNT_TYPE, this.townHolderProvider);
        Gringotts.instance.getAccountHolderFactory().registerAccountHolderProvider(NationAccountHolder.ACCOUNT_TYPE, this.nationHolderProvider);
    }

    /**
     * Vault created.
     *
     * @param event the event
     */
    @EventHandler
    public void vaultCreated(PlayerVaultCreationEvent event) {
        // some listener already claimed this event
        if (event.isValid() || !this.isEnabled()) {
            return;
        }

        String line2String = event.getCause().getLine(2);

        if (line2String == null) {
            return;
        }

        Player player = event.getCause().getPlayer();

        boolean forOther = line2String.length() > 0 && Permissions.CREATE_VAULT_ADMIN.isAllowed(player);

        AccountHolder owner;

        if (event.getType().equals(TownyConfiguration.CONF.townSignTypeName)) {
            if (!TownyPermissions.CREATE_VAULT_TOWN.isAllowed(player)) {
                player.sendMessage(TownyLanguage.LANG.noTownVaultPerm);

                return;
            }

            if (forOther) {
                owner = this.townHolderProvider.getAccountHolder(TownyUniverse.getInstance().getTown(line2String));

                if (owner == null) {
                    return;
                }
            } else {
                owner = this.townHolderProvider.getAccountHolder(player);
            }

            if (owner == null) {
                player.sendMessage(TownyLanguage.LANG.noTownResident);

                return;
            }

            if (CONF.vaultsOnlyInTowns && TownyAPI.getInstance().getTownBlock(event.getCause().getBlock().getLocation()) == null) {
                event.getCause().getPlayer().sendMessage(TownyLanguage.LANG.vaultNotInTown);
                return;
            }

            Town town = ((TownAccountHolder) owner).getTown();
            if (!town.hasMeta(defaultVaultCountField.getKey())) {
                town.addMetaData(defaultVaultCountField);
                event.setOwner(owner);
                event.setValid(true);
                return;
            }

            IntegerDataField townVaultCount = (IntegerDataField) town.getMetadata("vault_count");
            if (CONF.maxTownVaults == -1) { // Keep track of vaults created & return because there is no limit for them
                townVaultCount.setValue(townVaultCount.getValue() + 1);
                town.addMetaData(townVaultCount); // Saves to disk
                event.setOwner(owner);
                event.setValid(true);
                return;
            }

            if (townVaultCount.getValue() + 1 > CONF.maxTownVaults) {
                event.getCause().getPlayer().sendMessage(TownyLanguage.LANG.tooManyVaults
                        .replace("%max", String.valueOf(CONF.maxTownVaults))
                        .replace("%government", String.valueOf(owner.getType())));
                return;
            }

            townVaultCount.setValue(townVaultCount.getValue() + 1); // Saves to memory
            town.addMetaData(townVaultCount); // Saves to disk

            event.setOwner(owner);
            event.setValid(true);
        } else if (event.getType().equals(TownyConfiguration.CONF.nationSignTypeName)) {
            if (!TownyPermissions.CREATE_VAULT_NATION.isAllowed(player)) {
                player.sendMessage(TownyLanguage.LANG.noNationVaultPerm);

                return;
            }

            if (forOther) {
                owner = this.nationHolderProvider.getAccountHolder(TownyUniverse.getInstance().getNation(line2String));

                if (owner == null) {
                    return;
                }
            } else {
                owner = this.nationHolderProvider.getAccountHolder(player);
            }

            if (owner == null) {
                player.sendMessage(TownyLanguage.LANG.notInNation);

                return;
            }

            if (CONF.vaultsOnlyInTowns && TownyAPI.getInstance().getTownBlock(event.getCause().getBlock().getLocation()) == null) {
                event.getCause().getPlayer().sendMessage(TownyLanguage.LANG.vaultNotInTown);
                return;
            }

            Nation nation = ((NationAccountHolder) owner).getNation();
            if (!nation.hasMeta(defaultVaultCountField.getKey())) {
                nation.addMetaData(defaultVaultCountField);
                event.setOwner(owner);
                event.setValid(true);
                return;
            }

            IntegerDataField nationVaultCount = (IntegerDataField) nation.getMetadata("vault_count");
            if (CONF.maxNationVaults == -1) { // Keep track of vaults created & return because there is no limit for them
                nationVaultCount.setValue(nationVaultCount.getValue() + 1);
                nation.addMetaData(nationVaultCount); // Saves to disk
                event.setOwner(owner);
                event.setValid(true);
                return;
            }

            if (nationVaultCount.getValue() + 1 > CONF.maxNationVaults) {
                event.getCause().getPlayer().sendMessage(TownyLanguage.LANG.tooManyVaults
                        .replace("%max", String.valueOf(CONF.maxNationVaults))
                        .replace("%government", String.valueOf(owner.getType())));
                return;
            }

            nationVaultCount.setValue(nationVaultCount.getValue() + 1); // Saves to memory
            nation.addMetaData(nationVaultCount); // Saves to disk

            event.setOwner(owner);
            event.setValid(true);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onVaultOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        if (!Util.isValidInventory(event.getInventory().getType())) return;

        AccountChest accountChest = getVaultChest(event.getInventory());
        if (accountChest == null) return;

        AccountHolder owner = accountChest.getAccount().owner;
        if (!(owner instanceof TownAccountHolder) && !(owner instanceof NationAccountHolder)) return;

        // Admin bypass
        if (Permissions.CREATE_VAULT_ADMIN.isAllowed(player)) return;

        Resident resident = TownyUniverse.getInstance().getResident(player.getUniqueId());

        if (owner instanceof TownAccountHolder townOwner) {
            if (!canAccessTownVault(resident, townOwner.getTown())) {
                event.setCancelled(true);
                player.sendMessage(TownyLanguage.LANG.noTownVaultAccess);
            }
        } else if (owner instanceof NationAccountHolder nationOwner) {
            if (!canAccessNationVault(resident, nationOwner.getNation())) {
                event.setCancelled(true);
                player.sendMessage(TownyLanguage.LANG.noNationVaultAccess);
            }
        }
    }

    private boolean canAccessTownVault(Resident resident, Town town) {
        if (resident == null) return false;
        if (town.getMayor().equals(resident)) return true;
        try {
            return resident.getTown().equals(town) && resident.hasTownRank("assistant");
        } catch (NotRegisteredException e) {
            return false;
        }
    }

    private boolean canAccessNationVault(Resident resident, Nation nation) {
        if (resident == null) return false;
        try {
            if (nation.getCapital().getMayor().equals(resident)) return true;
        } catch (Exception e) { }
        try {
            return resident.getTown().getNation().equals(nation) && resident.hasNationRank("assistant");
        } catch (NotRegisteredException e) {
            return false;
        }
    }

    private AccountChest getVaultChest(Inventory inventory) {
        Location loc = inventory.getLocation();
        if (loc == null) {
            InventoryHolder holder = inventory.getHolder();
            if (holder instanceof DoubleChest doubleChest) {
                loc = ((org.bukkit.block.Chest) doubleChest.getLeftSide()).getLocation();
            }
        }
        if (loc == null) return null;

        final Location finalLoc = loc;
        for (AccountChest chest : gringotts.getDao().retrieveChests()) {
            if (!chest.isChestLoaded()) continue;
            if (chest.matchesLocation(finalLoc)) {
                return chest;
            }
        }
        return null;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        new BukkitRunnable() {
            @Override
            public void run() {
                Resident resident = TownyUniverse.getInstance().getResident(player.getUniqueId());
                if (resident == null) return;

                // Warn about full town vaults
                try {
                    Town town = resident.getTown();
                    if (canAccessTownVault(resident, town)) {
                        AccountHolder holder = townHolderProvider.getAccountHolder(town);
                        if (holder != null) {
                            GringottsAccount account = gringotts.getAccounting().getAccount(holder);
                            for (AccountChest chest : gringotts.getDao().retrieveChests(account)) {
                                if (!chest.isChestLoaded()) continue;
                                InventoryHolder inv = chest.chest();
                                if (inv == null) continue;
                                if (new AccountInventory(inv.getInventory()).isFull()) {
                                    Location loc = chest.chestLocation();
                                    if (loc == null) continue;
                                    player.sendMessage(TownyLanguage.LANG.town_vault_full
                                            .replace("%town", town.getName())
                                            .replace("%world", loc.getWorld().getName())
                                            .replace("%x", String.valueOf(loc.getBlockX()))
                                            .replace("%y", String.valueOf(loc.getBlockY()))
                                            .replace("%z", String.valueOf(loc.getBlockZ())));
                                }
                            }
                        }
                    }
                } catch (NotRegisteredException ignored) {}

                // Warn about full nation vaults
                try {
                    Nation nation = resident.getTown().getNation();
                    if (canAccessNationVault(resident, nation)) {
                        AccountHolder holder = nationHolderProvider.getAccountHolder(nation);
                        if (holder != null) {
                            GringottsAccount account = gringotts.getAccounting().getAccount(holder);
                            for (AccountChest chest : gringotts.getDao().retrieveChests(account)) {
                                if (!chest.isChestLoaded()) continue;
                                InventoryHolder inv = chest.chest();
                                if (inv == null) continue;
                                if (new AccountInventory(inv.getInventory()).isFull()) {
                                    Location loc = chest.chestLocation();
                                    if (loc == null) continue;
                                    player.sendMessage(TownyLanguage.LANG.nation_vault_full
                                            .replace("%nation", nation.getName())
                                            .replace("%world", loc.getWorld().getName())
                                            .replace("%x", String.valueOf(loc.getBlockX()))
                                            .replace("%y", String.valueOf(loc.getBlockY()))
                                            .replace("%z", String.valueOf(loc.getBlockZ())));
                                }
                            }
                        }
                    }
                } catch (NotRegisteredException ignored) {}
            }
        }.runTaskLater(gringotts, 20L);
    }

    @EventHandler
    public void vaultDeleted(BlockBreakEvent event) {
        if (!Util.isValidContainer(event.getBlock().getType()) && !Tag.SIGNS.isTagged(event.getBlock().getType())) return;

        Location blockLoc = event.getBlock().getLocation();
        List<AccountChest> chests = new ArrayList<>(gringotts.getDao().retrieveChests());

        // The block itself is actually a sign
        for (AccountChest chest : chests) {
            Location chestLoc = chest.chestLocation();
            if (chest.sign.getBlock().getLocation().equals(blockLoc) || (chestLoc != null && chestLoc.equals(blockLoc))) {

                AccountHolder owner = chest.getAccount().owner;

                switch (owner.getType()) {
                    case "town":
                        Town town = ((TownAccountHolder) owner).getTown();

                        IntegerDataField townVaultCount = (IntegerDataField) town.getMetadata("vault_count");
                        if (townVaultCount == null) break;
                        townVaultCount.setValue(townVaultCount.getValue() - 1); // Saves to memory
                        town.addMetaData(townVaultCount); // Saves to disk

                        break;

                    case "nation":
                        Nation nation = ((NationAccountHolder) owner).getNation();

                        IntegerDataField nationVaultCount = (IntegerDataField) nation.getMetadata("vault_count");
                        if (nationVaultCount == null) break;
                        nationVaultCount.setValue(nationVaultCount.getValue() - 1); // Saves to memory
                        nation.addMetaData(nationVaultCount); // Saves to disk

                        break;

                    default:
                        break;
                }
            }
        }
    }
}
