# VoidEconomy

A lightweight economy plugin for Paper 26.1.2. Balances use decimal-safe arithmetic and persist in `balances.yml`.

## Build and install

Requires Java 25, Maven 3.9+, and Paper 26.1.2. Run `mvn clean package`, then copy `target/void-economy-2.1.0.jar` into the Paper server's `plugins` folder.

Commands: `/menu` (or `/hub`) opens the mechanics hub. `/auction` (or `/ah`) opens the auction house, and `/ah sell <price>` lists the item held in the main hand. The 54-slot auction browser supports pagination, price sorting, categories, text search, refresh, personal listings, cancellation, purchases, expiration, and collection. The bank contains payments, transaction history, leaderboard, and privacy controls. `/admin` opens the permission-gated economy administration workflow.

`/bounty` opens the 54-slot bounty board. Players can place confirmed bounties, view the most-wanted list, and claim bounty rewards by killing a target. Minimum amount, placement cooldown, and operator protection are configurable.

The Bank also supports physical whole-number currency notes in $1, $5, $10, $50, $100, $500, and $1000 denominations. Notes use persistent custom IDs, so ordinary renamed paper cannot substitute for them in plugin mechanics or metadata-aware recipes. Right-click deposits one held note; sneak-right-click deposits the entire held stack.

The matching Minecraft 26.1.2 resource pack is built from the `resource-pack` directory. It gives genuine bank notes the `void_economy:bank_note` custom item model while ordinary paper keeps its vanilla texture.

Currency display, starting balance, messages, and save interval are configurable in `config.yml`.
