package net.inniria.wurm.grindcast;


import com.wurmonline.client.game.inventory.InventoryMetaItem;


public class SacrificableType {
	private String name;
	private float favor;
	
	public SacrificableType(String name, float favor){
		this.name = name.toLowerCase();
		this.favor = favor;
	}
	
	public float getFavor() {
		return this.favor;
	}
	
	public boolean isOfType(InventoryMetaItem item) {
		return item.getBaseName().contains(this.name);
	}
}
