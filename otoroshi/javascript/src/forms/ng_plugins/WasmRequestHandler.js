import WasmPlugin from './WasmPlugin';

export default {
  id: 'cp:otoroshi.next.plugins.WasmRequestHandler',
  config_schema: WasmPlugin.config_schema,
  config_flow: WasmPlugin.config_flow,
};
