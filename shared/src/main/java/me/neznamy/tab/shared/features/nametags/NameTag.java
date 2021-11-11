package me.neznamy.tab.shared.features.nametags;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import me.neznamy.tab.api.Property;
import me.neznamy.tab.api.TabFeature;
import me.neznamy.tab.api.TabPlayer;
import me.neznamy.tab.api.protocol.PacketPlayOutScoreboardTeam;
import me.neznamy.tab.api.team.TeamManager;
import me.neznamy.tab.shared.CpuConstants;
import me.neznamy.tab.shared.ITabPlayer;
import me.neznamy.tab.shared.PropertyUtils;
import me.neznamy.tab.shared.TAB;
import me.neznamy.tab.shared.features.NickCompatibility;
import me.neznamy.tab.shared.features.RedisSupport;
import me.neznamy.tab.shared.features.layout.LayoutManager;
import me.neznamy.tab.shared.features.sorting.Sorting;

public class NameTag extends TabFeature implements TeamManager {

	private boolean invisibleNametags;
	private Sorting sorting;
	private List<TabPlayer> hiddenNametag = new ArrayList<>();
	private Map<TabPlayer, List<TabPlayer>> hiddenNametagFor = new HashMap<>();
	private List<TabPlayer> teamHandlingPaused = new ArrayList<>();
	private Map<TabPlayer, String> forcedTeamName = new HashMap<>();
	private CollisionManager collisionManager;
	private boolean accepting18x;

	public NameTag() {
		super("Nametags", TAB.getInstance().getConfiguration().getConfig().getStringList("scoreboard-teams.disable-in-servers"),
				TAB.getInstance().getConfiguration().getConfig().getStringList("scoreboard-teams.disable-in-worlds"));
		invisibleNametags = TAB.getInstance().getConfiguration().getConfig().getBoolean("scoreboard-teams.invisible-nametags", false);
		boolean collisionRule = TAB.getInstance().getConfiguration().getConfig().getBoolean("scoreboard-teams.enable-collision", true);
		sorting = new Sorting(this);
		TAB.getInstance().getFeatureManager().registerFeature("sorting", sorting);
		accepting18x = TAB.getInstance().getPlatform().isProxy() || TAB.getInstance().getPlatform().isPluginEnabled("ViaRewind") || 
				TAB.getInstance().getPlatform().isPluginEnabled("ProtocolSupport") || TAB.getInstance().getServerVersion().getMinorVersion() == 8;
		if (accepting18x)
			TAB.getInstance().getFeatureManager().registerFeature("nametags-visibility", new VisibilityRefresher(this));
		if (TAB.getInstance().getServerVersion().getMinorVersion() >= 9) {
			collisionManager = new CollisionManager(this, collisionRule);
			TAB.getInstance().getFeatureManager().registerFeature("nametags-collision", collisionManager);
		}
		TAB.getInstance().debug(String.format("Loaded NameTag feature with parameters collisionRule=%s, disabledWorlds=%s, disabledServers=%s, invisibleNametags=%s",
				collisionRule, Arrays.toString(disabledWorlds), Arrays.toString(disabledServers), invisibleNametags));
	}

	@Override
	public void load(){
		for (TabPlayer all : TAB.getInstance().getOnlinePlayers()) {
			((ITabPlayer) all).setTeamName(getSorting().getTeamName(all));
			updateProperties(all);
			hiddenNametagFor.put(all, new ArrayList<>());
			if (isDisabled(all.getServer(), all.getWorld())) {
				addDisabledPlayer(all);
				continue;
			}
			registerTeam(all);
		}
	}

	@Override
	public void unload() {
		for (TabPlayer p : TAB.getInstance().getOnlinePlayers()) {
			if (!isDisabledPlayer(p)) unregisterTeam(p);
		}
	}

	@Override
	public void onLoginPacket(TabPlayer packetReceiver) {
		for (TabPlayer all : TAB.getInstance().getOnlinePlayers()) {
			if (!all.isLoaded()) continue;
			if (!isDisabledPlayer(all)) registerTeam(all, packetReceiver);
		}
	}

	@Override
	public void refresh(TabPlayer refreshed, boolean force) {
		if (isDisabledPlayer(refreshed)) return;
		boolean refresh;
		if (force) {
			updateProperties(refreshed);
			refresh = true;
		} else {
			boolean prefix = refreshed.getProperty(PropertyUtils.TAGPREFIX).update();
			boolean suffix = refreshed.getProperty(PropertyUtils.TAGSUFFIX).update();
			refresh = prefix || suffix;
		}

		if (refresh) updateTeam(refreshed);
	}

	@Override
	public void onJoin(TabPlayer connectedPlayer) {
		((ITabPlayer) connectedPlayer).setTeamName(getSorting().getTeamName(connectedPlayer));
		updateProperties(connectedPlayer);
		hiddenNametagFor.put(connectedPlayer, new ArrayList<>());
		for (TabPlayer all : TAB.getInstance().getOnlinePlayers()) {
			if (!all.isLoaded() || all == connectedPlayer) continue; //avoiding double registration
			if (!isDisabledPlayer(all)) {
				registerTeam(all, connectedPlayer);
			}
		}
		if (isDisabled(connectedPlayer.getServer(), connectedPlayer.getWorld())) {
			addDisabledPlayer(connectedPlayer);
			return;
		}
		registerTeam(connectedPlayer);
	}

	@Override
	public void onQuit(TabPlayer disconnectedPlayer) {
		super.onQuit(disconnectedPlayer);
		if (!isDisabledPlayer(disconnectedPlayer)) unregisterTeam(disconnectedPlayer);
		hiddenNametag.remove(disconnectedPlayer);
		hiddenNametagFor.remove(disconnectedPlayer);
		teamHandlingPaused.remove(disconnectedPlayer);
		forcedTeamName.remove(disconnectedPlayer);
		for (TabPlayer all : TAB.getInstance().getOnlinePlayers()) {
			if (all == disconnectedPlayer) continue;
			if (hiddenNametagFor.containsKey(all)) hiddenNametagFor.get(all).remove(disconnectedPlayer); //clearing memory from API method
		}
	}

	@Override
	public void onServerChange(TabPlayer p, String from, String to) {
		onWorldChange(p, null, null);
	}
	
	@Override
	public void onWorldChange(TabPlayer p, String from, String to) {
		boolean disabledBefore = isDisabledPlayer(p);
		boolean disabledNow = false;
		if (isDisabled(p.getServer(), p.getWorld())) {
			disabledNow = true;
			addDisabledPlayer(p);
		} else {
			removeDisabledPlayer(p);
		}
		updateProperties(p);
		if (disabledNow && !disabledBefore) {
			unregisterTeam(p);
		} else if (!disabledNow && disabledBefore) {
			registerTeam(p);
		} else {
			updateTeam(p);
		}
	}

	@Override
	public void hideNametag(TabPlayer player) {
		if (hiddenNametag.contains(player)) return;
		hiddenNametag.add(player);
		updateTeamData(player);
	}
	
	@Override
	public void hideNametag(TabPlayer player, TabPlayer viewer) {
		if (hiddenNametagFor.get(player).contains(viewer)) return;
		hiddenNametagFor.get(player).add(viewer);
		updateTeamData(player, viewer);
		if (player.getArmorStandManager() != null) player.getArmorStandManager().updateVisibility(true);
	}

	@Override
	public void showNametag(TabPlayer player) {
		if (!hiddenNametag.contains(player)) return;
		hiddenNametag.remove(player);
		updateTeamData(player);
	}
	
	@Override
	public void showNametag(TabPlayer player, TabPlayer viewer) {
		if (!hiddenNametagFor.get(player).contains(viewer)) return;
		hiddenNametagFor.get(player).remove(viewer);
		updateTeamData(player, viewer);
		if (player.getArmorStandManager() != null) player.getArmorStandManager().updateVisibility(true);
	}

	@Override
	public boolean hasHiddenNametag(TabPlayer player) {
		return hiddenNametag.contains(player);
	}

	@Override
	public boolean hasHiddenNametag(TabPlayer player, TabPlayer viewer) {
		return hiddenNametagFor.containsKey(player) && hiddenNametagFor.get(player).contains(viewer);
	}

	@Override
	public void pauseTeamHandling(TabPlayer player) {
		if (teamHandlingPaused.contains(player)) return;
		if (!isDisabledPlayer(player)) unregisterTeam(player);
		teamHandlingPaused.add(player); //adding after, so unregisterTeam method runs
	}

	@Override
	public void resumeTeamHandling(TabPlayer player) {
		if (!teamHandlingPaused.contains(player)) return;
		teamHandlingPaused.remove(player); //removing before, so registerTeam method runs
		if (!isDisabledPlayer(player)) registerTeam(player);
	}

	@Override
	public boolean hasTeamHandlingPaused(TabPlayer player) {
		return teamHandlingPaused.contains(player);
	}

	@Override
	public void forceTeamName(TabPlayer player, String name) {
		if (Objects.equals(forcedTeamName.get(player), name)) return;
		if (name != null && name.length() > 16) throw new IllegalArgumentException("Team name cannot be more than 16 characters long.");
		unregisterTeam(player);
		forcedTeamName.put(player, name);
		registerTeam(player);
		if (name != null) ((ITabPlayer)player).setTeamNameNote("Set using API");
		RedisSupport redis = (RedisSupport) TAB.getInstance().getFeatureManager().getFeature("redisbungee");
		if (redis != null) redis.updateTeamName(player, player.getTeamName());
	}

	@Override
	public String getForcedTeamName(TabPlayer player) {
		return forcedTeamName.get(player);
	}

	@Override
	public void setCollisionRule(TabPlayer player, Boolean collision) {
		collisionManager.setCollisionRule(player, collision);
	}

	@Override
	public Boolean getCollisionRule(TabPlayer player) {
		return collisionManager.getCollisionRule(player);
	}
	
	@Override
	public void updateTeamData(TabPlayer p) {
		Property tagprefix = p.getProperty(PropertyUtils.TAGPREFIX);
		Property tagsuffix = p.getProperty(PropertyUtils.TAGSUFFIX);
		for (TabPlayer viewer : TAB.getInstance().getOnlinePlayers()) {
			String currentPrefix = tagprefix.getFormat(viewer);
			String currentSuffix = tagsuffix.getFormat(viewer);
			boolean visible = getTeamVisibility(p, viewer);
			viewer.sendCustomPacket(new PacketPlayOutScoreboardTeam(p.getTeamName(), currentPrefix, currentSuffix, translate(visible), translate(collisionManager.getCollision(p)), 0), CpuConstants.PacketCategory.NAMETAGS_TEAM_UPDATE);
		}
		RedisSupport redis = (RedisSupport) TAB.getInstance().getFeatureManager().getFeature("redisbungee");
		if (redis != null) redis.updateNameTag(p, p.getProperty(PropertyUtils.TAGPREFIX).get(), p.getProperty(PropertyUtils.TAGSUFFIX).get());
	}

	public void updateTeamData(TabPlayer p, TabPlayer viewer) {
		Property tagprefix = p.getProperty(PropertyUtils.TAGPREFIX);
		Property tagsuffix = p.getProperty(PropertyUtils.TAGSUFFIX);
		boolean visible = getTeamVisibility(p, viewer);
		String currentPrefix = tagprefix.getFormat(viewer);
		String currentSuffix = tagsuffix.getFormat(viewer);
		viewer.sendCustomPacket(new PacketPlayOutScoreboardTeam(p.getTeamName(), currentPrefix, currentSuffix, translate(visible), translate(collisionManager.getCollision(p)), 0), CpuConstants.PacketCategory.NAMETAGS_TEAM_UPDATE);
	}

	public void unregisterTeam(TabPlayer p) {
		if (hasTeamHandlingPaused(p)) return;
		if (p.getTeamName() == null) return;
		for (TabPlayer viewer : TAB.getInstance().getOnlinePlayers()) {
			viewer.sendCustomPacket(new PacketPlayOutScoreboardTeam(p.getTeamName()), CpuConstants.PacketCategory.NAMETAGS_TEAM_UNREGISTER);
		}
	}

	public void registerTeam(TabPlayer p) {
		for (TabPlayer viewer : TAB.getInstance().getOnlinePlayers()) {
			registerTeam(p, viewer);
		}
	}

	private void registerTeam(TabPlayer p, TabPlayer viewer) {
		if (hasTeamHandlingPaused(p)) return;
		Property tagprefix = p.getProperty(PropertyUtils.TAGPREFIX);
		Property tagsuffix = p.getProperty(PropertyUtils.TAGSUFFIX);
		String replacedPrefix = tagprefix.getFormat(viewer);
		String replacedSuffix = tagsuffix.getFormat(viewer);
		if (viewer.getVersion().getMinorVersion() >= 8 && TAB.getInstance().getConfiguration().isUnregisterBeforeRegister()) {
			viewer.sendCustomPacket(new PacketPlayOutScoreboardTeam(p.getTeamName()), CpuConstants.PacketCategory.NAMETAGS_TEAM_UNREGISTER);
		}
		viewer.sendCustomPacket(new PacketPlayOutScoreboardTeam(p.getTeamName(), replacedPrefix, replacedSuffix, translate(getTeamVisibility(p, viewer)), 
				translate(collisionManager.getCollision(p)), Arrays.asList(getName(p)), 0), CpuConstants.PacketCategory.NAMETAGS_TEAM_REGISTER);
	}

	private void updateTeam(TabPlayer p) {
		if (p.getTeamName() == null) return; //player not loaded yet
		String newName = getSorting().getTeamName(p);
		if (p.getTeamName().equals(newName)) {
			updateTeamData(p);
		} else {
			unregisterTeam(p);
			LayoutManager layout = (LayoutManager) TAB.getInstance().getFeatureManager().getFeature("layout");
			if (layout != null) layout.updateTeamName(p, newName);
			((ITabPlayer) p).setTeamName(newName);
			registerTeam(p);
			RedisSupport redis = (RedisSupport) TAB.getInstance().getFeatureManager().getFeature("redisbungee");
			if (redis != null) redis.updateTeamName(p, p.getTeamName());
		}
	}

	public String translate(boolean b) {
		return b ? "always" : "never";
	}
	
	protected void updateProperties(TabPlayer p) {
		p.loadPropertyFromConfig(this, PropertyUtils.TAGPREFIX);
		p.loadPropertyFromConfig(this, PropertyUtils.TAGSUFFIX);
	}

	public boolean getTeamVisibility(TabPlayer p, TabPlayer viewer) {
		return !hasHiddenNametag(p) && !hasHiddenNametag(p, viewer) && !invisibleNametags && (!accepting18x || !p.hasInvisibilityPotion());
	}

	public Sorting getSorting() {
		return sorting;
	}

	public CollisionManager getCollisionManager() {
		return collisionManager;
	}
	
	private String getName(TabPlayer p) {
		NickCompatibility nick = (NickCompatibility) TAB.getInstance().getFeatureManager().getFeature("nick");
		if (nick != null) {
			return nick.getNickname(p);
		}
		return p.getName();
	}
}