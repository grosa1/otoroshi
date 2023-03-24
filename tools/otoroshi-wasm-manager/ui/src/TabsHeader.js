

export function TabsHeader({
  selectedPlugin, onSave, onBuild,
  showPlaySettings, showPublishSettings, children, closedSidebar }) {

  return <Header
    selectedPluginType={selectedPlugin?.type}
    onSave={onSave}
    closedSidebar={closedSidebar}
    onBuild={onBuild}
    showActions={!!selectedPlugin}
    showPlaySettings={showPlaySettings}
    showPublishSettings={showPublishSettings}>
    {children}
  </Header>
}

function Header({
  children, onSave, onBuild, showActions,
  showPlaySettings, showPublishSettings, selectedPluginType, closedSidebar }) {

  return <div className='d-flex align-items-center justify-content-between bg-light'
    style={{ position: 'fixed', height: 42, zIndex: 10, width: closedSidebar ? 'calc(100vw - 42px)' : 'calc(100vw - 250px)' }}>
    {children}

    <div className='d-flex align-items-center'>
      {showActions && <>
        <Save onSave={onSave} />
        <Build onBuild={onBuild} />
        {selectedPluginType !== 'go' && <Publish showPublishSettings={showPublishSettings} />}
      </>}
      <Play showPlaySettings={showPlaySettings} />
    </div>
  </div>
}

function Save({ onSave }) {
  return <button type="button"
    style={{ border: 'none', background: 'none' }}
    className="pe-2"
    onClick={onSave}>
    <i className='fas fa-save' />
  </button>
}

function Build({ onBuild }) {
  return <button type="button"
    style={{ border: 'none', background: 'none' }}
    className="pe-2"
    onClick={onBuild}>
    <i className='fas fa-hammer' />
  </button>
}

function Publish({ showPublishSettings }) {
  return <button type="button"
    style={{ border: 'none', background: 'none' }}
    className="pe-2"
    onClick={showPublishSettings}>
    <i className='fas fa-upload' />
  </button>
}

function Play({ showPlaySettings }) {
  return <button type="button"
    style={{ border: 'none', background: 'none' }}
    className="pe-2"
    onClick={showPlaySettings}
  >
    <i className='fas fa-play' />
  </button>
}