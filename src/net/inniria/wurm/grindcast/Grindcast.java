package net.inniria.wurm.grindcast;


import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.gotti.wurmunlimited.modloader.ReflectionUtil;
import org.gotti.wurmunlimited.modloader.classhooks.HookManager;
import org.gotti.wurmunlimited.modloader.interfaces.Configurable;
import org.gotti.wurmunlimited.modloader.interfaces.Initable;
import org.gotti.wurmunlimited.modloader.interfaces.PreInitable;
import org.gotti.wurmunlimited.modloader.interfaces.WurmClientMod;

import com.wurmonline.client.game.inventory.InventoryMetaItem;
import com.wurmonline.client.game.inventory.InventoryMetaWindowManager;
import com.wurmonline.client.game.inventory.InventoryMetaWindowView;
import com.wurmonline.client.renderer.gui.HeadsUpDisplay;
import com.wurmonline.client.renderer.gui.HealthBar;
import com.wurmonline.client.renderer.gui.ToolBelt;
import com.wurmonline.shared.constants.PlayerAction;

import javassist.ClassPool;
import javassist.CtClass;


public class Grindcast implements WurmClientMod, Initable, PreInitable, Configurable {
	public static Logger logger = Logger.getLogger("Grindcast");
    public static HeadsUpDisplay hud;
    
    public static boolean toggled = true;
    public static LocalDateTime lastSacrifice = LocalDateTime.now();;
    public static ArrayList<SacrificableType> sacrificableTypes;
    
	@Override
	public void configure(Properties properties) {
		sacrificableTypes = new ArrayList<>();
		
		// Parse list of sacrificable items
		String sacRaw = properties.getProperty("sacrificables", "");
		for(String cand: sacRaw.split(";")){
			if(cand.isEmpty()) continue;
			
			String[] parts = cand.split(":");
			if(parts.length != 2) continue;
			
			sacrificableTypes.add(new SacrificableType(
				parts[0].trim(),
				Float.parseFloat(parts[1].trim())
			));
		}
	}
	
    @Override
    public void preInit() {}
    
    @Override
    public void init() {
    	try {
    		ClassPool classPool = HookManager.getInstance().getClassPool();
    		
            // Add console handler
	        CtClass ctWurmConsole = classPool.getCtClass("com.wurmonline.client.console.WurmConsole");
	        ctWurmConsole.getMethod("handleDevInput", "(Ljava/lang/String;[Ljava/lang/String;)Z").insertBefore(
	            "if (net.inniria.wurm.grindcast.Grindcast.handleCommand($1,$2)) return true;"
	        );
	        
	        // Add message handler
	        CtClass ctWurmChat = classPool.getCtClass("com.wurmonline.client.renderer.gui.ChatPanelComponent");
	        ctWurmChat.getMethod("addText", "(Ljava/lang/String;Ljava/lang/String;FFFZ)V").insertBefore(
	                "net.inniria.wurm.grindcast.Grindcast.handleMessage($1,$2);"
	        );
	        
    	}catch(Throwable e){
        	logger.log(Level.SEVERE, "Error loading mod", e.getMessage());
        }
    	
        // Hook HUD init to grab instance on creation
        HookManager.getInstance().registerHook("com.wurmonline.client.renderer.gui.HeadsUpDisplay", "init", "(II)V", () -> (proxy, method, args) -> {
			method.invoke(proxy, args);
			hud = (HeadsUpDisplay) proxy;
			return null;
		});
    }
    
    public static boolean handleCommand(final String cmd, final String[] data) {
    	if(!cmd.equalsIgnoreCase("togglegrindcast")) return false;
    	toggled = !toggled;
    	hud.consoleOutput("Grindcast has been " + ((toggled) ? "enabled" : "disabled") + ".");
    	return true;
    }
    
    public static void handleMessage(String context, String message) {
    	if(!(toggled && context.equalsIgnoreCase(":event"))) return;
    	if(!message.contains("need more favor with your god to cast that spell")) return;
    	if(Duration.between(lastSacrifice, LocalDateTime.now()).getSeconds() < 10) return;
		lastSacrifice = LocalDateTime.now();
    	
    	// Get altar ID
		long altarId = Grindcast.getAltarId();
		if(altarId == -1) {
			hud.textMessage(":Event", 1.0F, 1.0F, 1.0F, "Could not find an altar to sacrifice at.");
			return;
		}
		
		// List potential sacrificables, check favor to refill
		ArrayList<InventoryMetaItem> sacrificables = Grindcast.getSacrificables();
		float toRefill = Grindcast.getMaxFavor() - Math.max(Grindcast.getFavor(), 12);
		float refilled = 0;
		
		// If no sacrificables found, return
		if(sacrificables.size() == 0) {
			hud.textMessage(":Event", 1.0F, 1.0F, 1.0F, "Could not find sacrificables for the sacrifice.");
			return;
		}
		
		// Start sacrificing
		// hud.sendAction(PlayerAction.PRAY, altarId);
		hud.sendAction(PlayerAction.SACRIFICE, altarId);
		
		// List items to sacrifice
		ArrayList<InventoryMetaItem> toSacrifice = new ArrayList<>();
		for(InventoryMetaItem item: sacrificables) {
			for(SacrificableType type: sacrificableTypes) {
				if(type.isOfType(item)) {
					toSacrifice.add(item);
					refilled += type.getFavor();
					break;
				}
			}
			
			// If enough items to refill favor, break
			if(refilled >= toRefill) break;
		}
		
		// Choose amount to move
		long[] toSacrificeIds = new long[Math.min(toSacrifice.size(), 100)];
		for(int i=0;i<toSacrificeIds.length;i++) {
			toSacrificeIds[i] = toSacrifice.get(i).getId();
		}
		
		// Move sacrificables to altar
		hud.getWorld().getServerConnection().sendMoveSomeItems(altarId, toSacrificeIds);
    }
    
    private static float getMaxFavor() {
    	try {
    		// Read faith from health bar
    		HealthBar hbar = hud.getHealthBar();
        	return ReflectionUtil.getPrivateField(hbar, ReflectionUtil.getField(HealthBar.class, "faithValue"));
    	}catch(Exception ex) {
    		// If read failed, output an error message and return 0
    		hud.consoleOutput("Grindcast: Error reading amount of faith.");
    		return 0;
    	}
    }
    
    private static float getFavor() {
    	try {
    		// Read favor from health bar
    		HealthBar hbar = hud.getHealthBar();
        	return ReflectionUtil.getPrivateField(hbar, ReflectionUtil.getField(HealthBar.class, "favorValue"));
    	}catch(Exception ex) {
    		// If read failed, output an error message and return 0
    		hud.consoleOutput("Grindcast: Error reading amount of favor.");
    		return 0;
    	}
    }
    
    private static long getAltarId() {
    	// First look for altar in inventories
    	ArrayList<InventoryMetaWindowView> inventories = Grindcast.getOpenInventories();
    	for(InventoryMetaWindowView inventory: inventories) {
    		InventoryMetaItem root = inventory.getRootItem();
    		if(root.getBaseName().contains("altar")) return root.getId();
    	}
    	
    	return -1;
    }
    
    private static ArrayList<InventoryMetaItem> getSacrificables(){
    	// Initialize items list
    	ArrayList<InventoryMetaItem> candidates = new ArrayList<>();
    	for(InventoryMetaWindowView inventory: Grindcast.getOpenInventories()) {
    		InventoryMetaItem root = inventory.getRootItem();
    		if(!root.getBaseName().contains("altar")) candidates.add(root);
    	}
    	
    	// Browse item hierarchy, adding sacrificables to list
    	ArrayList<InventoryMetaItem> unfiltered = new ArrayList<>();
    	while(!candidates.isEmpty()) {
    		// Update candidates list
    		InventoryMetaItem candidate = candidates.remove(0);
    		List<InventoryMetaItem> children = candidate.getChildren();
    		if(children != null) candidates.addAll(children);
    		
    		// If item is a sacrificable, add to list
    		for(SacrificableType type: sacrificableTypes) {
    			if(type.isOfType(candidate)){
    				unfiltered.add(candidate);
    				break;
    			}
    		}
    	}
    	
    	// Filter elements, removing duplicates
    	ArrayList<InventoryMetaItem> ret = new ArrayList<>();
    	for(InventoryMetaItem u: unfiltered) {
    		if(Grindcast.listContains(ret, u)) continue;
    		ret.add(u);
    	}
    	
    	return ret;
    }
    
    private static ArrayList<InventoryMetaWindowView> getOpenInventories() {
    	InventoryMetaWindowManager invManager = hud.getWorld().getInventoryManager();
    	
    	// List inventories, starting with player inventory
    	ArrayList<InventoryMetaWindowView> inventories = new ArrayList<>();
		inventories.add(invManager.getPlayerInventory());
		
		// Add other inventories to list
    	try {
    		Map<Long, InventoryMetaWindowView> extraInvs = ReflectionUtil.getPrivateField(invManager, ReflectionUtil.getField(InventoryMetaWindowManager.class, "inventoryWindows"));
    		inventories.addAll(new ArrayList<>(extraInvs.values()));
    	}catch(Exception ex) {
    		hud.consoleOutput("I2Improve: Error accessing extra inventory windows.");
    	}
    	
    	return inventories;
    }
    
    private static boolean listContains(ArrayList<InventoryMetaItem> list, InventoryMetaItem obj){
    	long objId = obj.getId();
    	for(InventoryMetaItem item: list) {
    		if(item.getId() == objId) return true;
    	}
    	
    	return false;
    }
}
