[![Discord](https://img.shields.io/discord/1470817972972032153?label=discord&labelColor=C8EAFF&logo=discord&logoColor=black&color=FBF1B3)](https://discord.gg/mkysGtjVsX) [![CurseForge](https://img.shields.io/curseforge/dt/1442073?labelColor=C8EAFF&label=curseforge&logo=curseforge&logoColor=black&color=FBF1B3)](https://www.curseforge.com/hytale/mods/bbb)

<span style="color:#ba372a"><strong>This plugin is intended to be used with the <em>Hytale Bridge</em> Blockbench plugin installed. You cannot connect to Blockbench without it. You can find it [here](https://www.blockbench.net/plugins/hytale_bridge).</strong></span>

Blockbench Bridge allows you to edit a Hytale world's assets (`.blockymodel` & `.png` files specifically) without leaving Blockbench, even on remote servers!

You can connect a Blockbench client to the server by running `/blockbench` in the console or as a player with the correct permissions. You will receive an authorisation key which you can input alongside the server's connection address (with the Blockbench-specific port) to form a bridge and sync all files!

Once connected, you'll see all of the asset packs installed on your world, including all of their Common files. You're then free to open any model/texture into blockbench or save any of your creations into your own asset packs. Folder creation/management is also supported, so just Blockbench can manage your entire Common directory!

<sup>Note this plugin is still in beta and some minor bugs are to be expected. I have many more ideas on how to further improve this project, and I'd love to hear all of your thoughts and feedback.</sup>

## For Servers: How to setup
Once the mod is installed, a folder for Blockbench Bridge will be created in `mods/` that contains the config json. The config has a port that must be set to an empty port on your server. To open new ports on your server, consult your hosting service.

<sup>By default, the plugin uses port <code>8651</code> on the server.</sup>