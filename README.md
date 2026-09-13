# Intellium 🚀

**Intellium** is a hardware-aware, low-level graphical optimization mod for Minecraft 1.21.1+, built specifically to unleash the full potential of **Intel GPU architectures** (Intel UHD, Iris Xe, and Intel Arc).

## 🌟 Key Features

* **Multi-Path Intel Capability System:** Automatically detects your hardware and scales the rendering pipeline from Intel UHD to Intel Arc.
* **Asynchronous Passive Staging:** Hooks into Sodium after chunk meshing (`@At("RETURN")`), offloading geometry processing to background worker threads.
* **Active Native Resource Reclaim:** Completely eliminates memory leaks on 8GB RAM systems using safe native buffer reclamation hooks.
* **100% Iris Shaders Compatible:** Retains pristine texture atlases and lightning maps.

## 🤝 Recommended Synergy

For an absolute buttery-smooth experience on budget hardware, we highly recommend pairing Intellium with **C2ME (Concurrent Chunk Management Engine)**.
