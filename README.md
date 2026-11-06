# FailMultiWorld (MWN)

Virtuální servery (skupiny světů) uvnitř jedné instance Paper/Purpur 1.21.4+ (Java 21). Autor: Failxos.

> **Stav: verze 1.0.0 – první kostra, NEPROŠLA kompilací ani testem na reálném serveru.**
> Kód vznikl v prostředí bez přístupu k Paper API, takže ho ověřila jen kontrola syntaxe.
> Při prvním buildu na GitHubu mohou vyskočit drobné chyby kompilace; pošli výpis chyb a opraví se.

## Build přes GitHub
1. Nahraj obsah této složky do GitHub repozitáře (klíčové je `pom.xml` a `.github/workflows/build.yml`).
2. Záložka **Actions** → workflow *Build plugin* se spustí sám (nebo *Run workflow*).
3. Hotový JAR najdeš v *Artifacts* → `FailMultiWorld`.
4. JAR dej do `plugins/`, restartuj server. Volitelně: PlaceholderAPI, LuckPerms, Vault.

Lokálně: `mvn clean package` → `target/FailMultiWorld-1.0.0.jar`.

## Co je implementováno
- **Vytváření serverů**: `/mwn create <name>` (svět + skupina + trvalé ID `server_001` + spawn + uložení).
- **Světy**: `/mwn world create|import|delete|assign|unassign|list|info`, `--group`, `--environment`, `--seed`;
  mazání světů i skupin jen přes potvrzení (`confirm`, 30 s) a bezpečnostní kontroly (prázdný svět, hlavní svět se nemaže, ověření cesty).
- **Chytré párování světů**: `ChunkBase`, `ChunkBase_Nether`, `ChunkBase_End`, aliasy v `config.yml`, skóre jistoty, `/mwn suggest [apply]`.
  Automaticky se přiřazuje jen nad prahem a nikdy při nejednoznačnosti.
- **Izolovaný chat** po skupinách + globální/staff chat, soukromé zprávy, ignorování, cooldown, filtr slov, mute chatu skupiny, oznámení skupině.
- **Izolace dat hráče** po kategoriích (inventář+zbroj+offhand, ender chest, XP, zdraví/jídlo/efekty, herní režim+létání, poloha)
  – každá kategorie je u skupiny zvlášť nebo sdílená. Přepnutí je jedna DB transakce, hráč se změní až po úspěšném načtení,
  nedokončené přenosy se po restartu zotaví, odpojení uprostřed přepnutí neztratí data, autosave, ukládání při vypnutí.
- **Přepínání**: `/server`, `/servers`, `/server <name>`, limity hráčů, údržba, cooldown, zvuk, zprávy, vstupní práva, událost `GroupSwitchEvent`.
  Přechod do světa jiné skupiny (např. přes jiný plugin) přepne data automaticky.
- **Plugin Isolation**: přepínače funkcí po skupinách (`/mwn feature`). Reálně vynucuje blokování příkazů pluginu
  (`isolation.features.<Feature>.commands`) a API `MwnApi#isFeatureEnabled(player, "Feature")`, které volají jiné pluginy.
  Cizí pluginy se nikdy nevypínají ani nepatchují; bez API nebo příkazů plná izolace není možná.
- **PlaceholderAPI**: `%mwn_group%`, `_group_name`, `_group_displayname`, `_group_players`, `_group_max_players`, `_total_players`,
  `_server_name`, `_server_status`, `_player_group`, `_player_world`, `_group_online`, `_group_description`, `_group_is_maintenance`,
  `_group_id`, `_feature_<Feature>`; většina jde i s klíčem skupiny, např. `%mwn_group_players_survival%`.
- **Formátování**: `&` kódy, `&#RRGGBB`, MiniMessage (gradient, rainbow, click, hover…) všude v pluginu.
- **Pravidla světa po skupinách**: obtížnost, pvp, hlad, fall-damage, spawn mobů, stavění, výbuchy, šíření ohně, regenerace (`/mwn set`).
- **Scoreboard a tab** po skupinách (placeholdery, refresh, header/footer, jména, skrytí hráčů z jiných skupin).
- **Portály** (`/mwn portal pos1|pos2|create|delete|toggle|list`, `portals.yml`, oprávnění `mwn.portal.<name>`) a **admin GUI** (`/mwn gui`).
- **Přátelé** (`/mwn friend add|accept|deny|remove|list|join`, notifikace, viditelnost serveru), **čas hraní po serverech** a **profil** (`/mwn profile`, `%mwn_playtime%`, `%mwn_playtime_total%`, `%mwn_friends%`).
- **Animace** (`animations:` v `config.yml`, `%mwn_anim_<name>%`) a **resource pack po skupinách** (`/mwn set <group> pack-url|pack-hash`; klient má jen jednu sadu packů, při přechodu se pošle pack cíle).
- **Fronty serverů** (plný server → fronta, pozice, priorita `mwn.queue.priority`, `/mwn queue [leave]`; odhad čekání se záměrně nezobrazuje, protože není spolehlivý).
- **Ekonomika po skupinách** (volitelně): `economy.provider: true` zaregistruje MWN jako Vault ekonomiku, skupina s `separate-economy: true` má vlastní zůstatek, ostatní sdílený.
  Zapínej jen pokud MWN má NAHRADIT stávající ekonomický plugin (zůstatky se neimportují). Placeholder `%mwn_balance%`.
- **Obnova záloh**: `/mwn restore <group> [soubor] confirm` – ověří archiv, skupina musí být prázdná, staré složky světů se zachovají jako `*.pre-restore-*`, při chybě rollback.
- **Selektor serverů (GUI)** s ochranou proti vytahování itemů.
- **Zálohy**: `/mwn backup <group>`, `/mwn backups <group>` (zip, ověření, retence).
- **Databáze**: SQLite (výchozí), MySQL, MariaDB; vše asynchronně, transakce.
- **Diagnostika**: `/mwn status|diagnostics|integrations|debug`.
- **API**: `MwnApi` (skupiny, hráč → skupina, přiřazení světů, přepnutí, zprávy skupině, vlastní placeholdery, kontrola funkcí) a události.

## NENÍ implementováno (záměrně se nepředstírá)
Hologramy jako samostatný modul (jdou přes PlaceholderAPI, např. DecentHolograms), statistiky mimo čas hraní, lokální chat podle vzdálenosti,
stránkování selektoru, banky ve Vault ekonomice, automatizované testy.
Přejmenování (`/mwn rename`) mění jen název skupiny, ne složky světů. Spuštění `restore` na hlavním světě serveru je zakázáno.

## Příkazy
Hráč: `/server [group]`, `/servers`, `/mwn chat [group|global|staff]`, `/mwn globalchat|staffchat <msg>`, `/mwn msg <player> <msg>`, `/mwn reply <msg>`, `/mwn ignore <player>`, `/mwn list|info <group>`, `/mwn friend …`, `/mwn profile`, `/mwn queue [leave]`, `/mwn help|version`.
Admin: `/mwn create|delete|rename|group|ungroup|world …|suggest|setspawn|setlobby|maintenance|set|feature|mutechat|announce|backup|backups|status|diagnostics|integrations|debug|reload`.
Nastavení `/mwn set <group> <klíč> <hodnota>`: display-name, description, icon, gui-slot, difficulty, default-gamemode, max-players,
isolated-chat, separate-inventory/enderchest/xp/health/gamemode, scoreboard, tablist, pvp, hunger, fall-damage, mob-spawning, build, explosions, fire-spread, natural-regeneration.

## Oprávnění
`mwn.admin` (vše), `mwn.create`, `mwn.delete`, `mwn.backup`, `mwn.use`, `mwn.server`, `mwn.chat.global`, `mwn.chat.staff`, `mwn.chat.msg`,
`mwn.bypass.limit|maintenance|cooldown|build|chat`, `mwn.queue.priority`, `mwn.portal.<name>`, `mwn.group.<group>` / `mwn.group.*` (jen když `require-group-permission: true`).
Oprávnění se kontrolují přes Bukkit, takže fungují s LuckPerms; plugin žádná oprávnění nepřepisuje.

## Testy
`mvn test` spouští jednotkové testy čisté logiky (barvy, párování světů, validace, profily dat). Ruční plán pro reálný server je v `docs/TESTING.md`.

## Soubory
`config.yml` (vše hlavní + scoreboard, tab, GUI, chat, izolace), `messages.yml` (všechny texty), `groups.yml` (skupiny, spravuje plugin),
`worlds.yml` (světy vytvořené pluginem), `data.db` (SQLite).

## API pro vývojáře
```java
MwnApi api = MwnApi.get();
Group g = api.getPlayerGroup(player);
if (!api.isFeatureEnabled(player, "PlaytimeRewards")) return; // v této skupině nepočítat
api.registerPlaceholder("custom", op -> "hodnota"); // %mwn_custom%
```

## Známá omezení
Světy se vytvářejí na hlavním vlákně (u velkých světů krátký lag). Skupiny sdílejí JVM a zdroje serveru.
Skrývání hráčů v tabu používá `hidePlayer`. Scoreboard pluginu může kolidovat s jiným scoreboard pluginem.
Počty bodů u scoreboardu (červená čísla) se nezakrývají.
