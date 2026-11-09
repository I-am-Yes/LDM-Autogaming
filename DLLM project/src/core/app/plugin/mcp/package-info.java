/**
 * Local MCP transport and the command bridge into the battle simulation.
 * {@link core.app.plugin.mcp.MCP} handles HTTP, protocol validation, tool schemas,
 * and client activity; {@link core.app.plugin.mcp.GameControl} queues state reads
 * and player actions for execution by the game thread.
 *
 * <p>State uses pixel coordinates from the top-left, with positive x pointing
 * right and positive y pointing down. AI actions advance the shared battle in
 * fixed simulation updates and wait between calls so model response time does
 * not advance gameplay. Game speed changes update cadence while preserving
 * fixed step durations and requested frame counts. Terminal commands use the
 * same bridge.
 */
package core.app.plugin.mcp;
