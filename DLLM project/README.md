# Tiny Battle

A simple Java 2D battle game using the textures in `assets/` and the standard
AWT/Swing libraries. Run `core.app.Main` in IntelliJ IDEA.

- **WASD / arrow keys:** move.
- **Mouse:** aim.
- **Hold left mouse / Space:** fire.
- **P:** pause or resume.
- **R:** restart.
- **Escape:** take back keyboard and mouse control from the AI.
- **T:** open chat. **/** opens the command line. **Enter** sends, **Escape** closes.

Defeat the enemy units to start the next wave. Both sides fire bullets and take
damage. The game ends when your health reaches zero.

Simulation uses fixed 1/60-second steps; rendering targets 120 frames per
second. Game speed defaults to **1x**. The **Game speed** dropdown provides
1x, 2x, 4x, 8x, and 16x, or use `/speed <multiplier>` for any value from 1 to 16
(for example, `/speed 1.5`). `/speed` shows the current speed.
Switching away from the window suspends manual gameplay.

To compile and run from PowerShell in the project folder (JDK 17 or newer):

```powershell
$sources = Get-ChildItem src -Recurse -Filter *.java | ForEach-Object FullName
javac -d out $sources
java -cp out core.app.Main
```

## Local LLM control through MCP

Starting the game also starts an MCP server at **http://127.0.0.1:8765/mcp**.
Connect an MCP client that supports the **Streamable HTTP** transport, then
select your local model in that client. The game supplies tools; your model's
application is responsible for calling them.

The endpoint supports MCP 2026-07-28 and the classic 2025-11-25, 2025-06-18,
and 2025-03-26 protocols, using JSON responses over
[Streamable HTTP](https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http).

For clients that accept a URL in their `mcpServers` configuration:

```json
{
  "mcpServers": {
    "battle-game": {
      "url": "http://127.0.0.1:8765/mcp"
    }
  }
}
```

Available tools:

| Tool | Purpose |
| --- | --- |
| `get_game_state` | Read player/enemy positions, health, bullet positions and velocities, score, wave, and control mode. |
| `control_player` | Move, aim, and optionally fire for 1–120 simulation frames. |
| `restart_game` | Reset the battle and wait for AI actions. |
| `release_control` | Return movement and shooting to the keyboard and mouse. |
| `set_game_speed` | Set `multiplier` from 1 to 16 without taking control or unpausing. |

Example `control_player` arguments:

```json
{
  "move_x": 1,
  "move_y": -1,
  "aim_x": 700,
  "aim_y": 200,
  "shoot": true,
  "frames": 30
}
```

Coordinates are pixels from the top-left: x increases right, y increases down.
Movement components range from -1 to 1; diagonal movement is normalized.
30 frames is half a simulation second. At 2x speed those frames run in roughly
a quarter of a wall-clock second. Each action returns the state after its final
frame; player and enemy actions advance together. Speed changes affect update
cadence, preserving fixed step duration, cooldowns, and requested frame counts.
State snapshots include `game_speed` and `target_updates_per_second`.

The first `control_player` call takes AI control. Between AI actions, the game
waits while the model decides what to do next. AI actions also work when the
game window is unfocused. `get_game_state` alone does not take control.
Restarting through MCP leaves the game waiting for the next AI action.
The server stops when the window closes.

Suggested model instruction: "Call restart_game, inspect the player, enemies,
and bullets, then use control_player to dodge and aim at enemies. Use short
actions and inspect each returned state. Call release_control when finished."

The server listens only on the local machine. If port 8765 is occupied, run
with `java -Dbattle.mcp.port=8766 -cp out core.app.Main`, or add
`-Dbattle.mcp.port=8766` to the IntelliJ run configuration's VM options.
The actual endpoint or a startup error appears in the run console.

## In-game chat and commands

The terminal works as a Minecraft-style overlay inside the battlefield.
Press **T** to type a local chat message, or **/** to type a command. Recent
messages appear above the input area and fade when chat is closed. Use
**Up/Down** to recall commands. Movement and shooting input are suppressed
while typing.

Try `/help`, `/clients`, `/state`, `/pause`, `/resume`, `/restart`, or `/release`.
You can also use `/move <x> <y> [frames]`, `/aim <x> <y>`, `/fire [frames]`,
and `/step [frames]`. `/next step` is a convenient alias for `/step 1` and
advances exactly one simulation tick at every speed. `/speed [multiplier]`
reads or changes the speed; the dropdown stays synchronized with command and
MCP changes. Speed persists when a battle is restarted. Movement components
range from -1 to 1; actions use the same game-thread queue as MCP commands.

The HUD shows the MCP server status and recently active client names and
versions. `/clients` shows the full list. Names are reported by the MCP client
application; a request without identifying metadata is labeled unknown.
Legacy clients are tracked with MCP session IDs, and modern clients report
their identity on each request.

Connection notices, running commands, and their success or failure are shown
in chat and printed to the run console with timestamps. Clients become idle
after one minute without requests and leave the recent-client list after five
minutes. An ended legacy session is shown as disconnected immediately.
