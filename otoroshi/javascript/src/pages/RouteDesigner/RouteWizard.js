import React, { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { TextInput } from '../../components/inputs'
import { getOldPlugins, getPlugins, nextClient } from '../../services/BackOfficeServices'
import Loader from './Loader'

const RouteNameStep = ({ state, onChange }) => <>
    <h3>Let's start with a name for your route</h3>

    <div className=''>
        <label className='mb-2'>Route name</label>
        <TextInput
            placeholder="Your route name..."
            flex={true}
            className="my-3"
            style={{
                fontSize: '2em',
                color: "#f9b000"
            }}
            label="Route name"
            value={state.route.name}
            onChange={onChange} />
    </div>
</>

const RouteChooser = ({ state, onChange }) => <>
    <h3>Select a route template</h3>
    <div style={{
        display: 'flex',
        flexDirection: 'column',
        gap: '10px',
    }}>
        {[
            { kind: 'api', title: 'NEW API', text: 'Add all the plugins you need to expose an api' },
            { kind: 'empty', title: 'EMPTY API', text: 'From scratch, no plugin added' },
            { kind: 'mock', title: 'MOCK API', text: 'Build a mock API with Charlatan' },
            { kind: 'graphql', title: 'GraphQL Schema', text: 'Start using GraphQL by creating your first schema' },
            { kind: 'graphql-proxy', title: 'GRAPHQL API', text: 'Secure a GraphQL API with Otoroshi' },
            { kind: 'webapp', title: 'WEBAPP', text: 'Add all the plugins you need to expose a webapp with authentication' },
        ].map(({ kind, title, text }) => (
            <button className={`btn ${state.route.kind === kind ? 'btn-save' : 'btn-dark'} py-3 wizard-route-chooser`}
                onClick={() => onChange(kind)}
                key={kind}
            >
                <h3 className='wizard-h3--small'>{title}</h3>
                <label style={{
                    flex: 1,
                    display: 'flex',
                    alignItems: 'center'
                }}>{text}</label>
            </button>
        ))}
    </div>
</>

const FrontendStep = ({ state, onChange }) => <>
    <h3>Expose your service over the world</h3>
    <div className=''>
        <label className='mb-2'>Domain name</label>
        <TextInput
            placeholder="Your domain name..."
            flex={true}
            className="my-3"
            value={state.route.domain}
            onChange={onChange} />
    </div>
</>

const BackendStep = ({ state, onChange, onError, error }) => {
    const checkChange = e => {
        try {
            if (!e.includes("://"))
                onError("Missing protocol")
            else {
                new URL(e)
                onError(false)
            }
        } catch (err) {
            onError(err.message)
        }
        onChange(e)
    }

    const sentences = {
        'graphql-proxy': {
            title: 'Endpoint',
            text: 'Your endpoint'
        }
    }

    return <>
        <h3>Define the target to redirect traffic</h3>
        <div className=''>
            <label className='mb-2'>{sentences[state.route.kind]?.title || 'Target URL'}</label>
            <TextInput
                placeholder={sentences[state.route.kind]?.text || "Your target URL..."}
                flex={true}
                className="my-3"
                value={state.route.url}
                onChange={checkChange} />
            <label style={{ color: "#D5443F" }}>{error}</label>
        </div>
    </>
}

const ProcessStep = ({ state, history }) => {
    const [loading, setLoading] = useState(true)
    const [createdRoute, setCreatedRoute] = useState({})

    const PLUGINS = {
        api: [
            'cp:otoroshi.next.plugins.ForceHttpsTraffic',
            'cp:otoroshi.next.plugins.Cors',
            'cp:otoroshi.next.plugins.DisableHttp10',
            'cp:otoroshi.next.plugins.ApikeyCalls',
            'cp:otoroshi.next.plugins.OverrideHost',
            'cp:otoroshi.next.plugins.XForwardedHeaders',
            'cp:otoroshi.next.plugins.OtoroshiInfos',
            'cp:otoroshi.next.plugins.SendOtoroshiHeadersBack',
            'cp:otoroshi.next.plugins.OtoroshiChallenge'
        ],
        webapp: [
            'cp:otoroshi.next.plugins.ForceHttpsTraffic',
            'cp:otoroshi.next.plugins.BuildMode',
            'cp:otoroshi.next.plugins.MaintenanceMode',
            'cp:otoroshi.next.plugins.DisableHttp10',
            'cp:otoroshi.next.plugins.AuthModule',
            'cp:otoroshi.next.plugins.OverrideHost',
            'cp:otoroshi.next.plugins.OtoroshiInfos',
            'cp:otoroshi.next.plugins.OtoroshiChallenge',
            'cp:otoroshi.next.plugins.GzipResponseCompressor',
        ],
        empty: [],
        'graphql-proxy': [
            'cp:otoroshi.next.plugins.GraphQLProxy'
        ],
        graphql: [
            'cp:otoroshi.next.plugins.GraphQLBackend'
        ],
        mock: [
            'cp:otoroshi.next.plugins.MockResponses'
        ]
    }

    useEffect(() => {
        Promise.all([getPlugins(), getOldPlugins(), nextClient
            .template(nextClient.ENTITIES.ROUTES)])
            .then(([plugins, oldPlugins, template]) => {
                const url = new URL(state.route.url)
                const secured = url.protocol.includes("https")

                const selectedPlugins = PLUGINS[state.route.kind]

                nextClient
                    .create(nextClient.ENTITIES.ROUTES, {
                        ...template,
                        enabled: false,
                        name: state.route.name,
                        frontend: {
                            ...template.frontend,
                            domains: [state.route.domain]
                        },
                        plugins: [...plugins, ...oldPlugins]
                            .filter(f => selectedPlugins.includes(f.id))
                            .map(plugin => {
                                return {
                                    config: plugin.default_config,
                                    debug: false,
                                    enabled: true,
                                    exclude: [],
                                    include: [],
                                    plugin: plugin.id
                                }
                            }),
                        backend: {
                            ...template.backend,
                            root: url.pathname,
                            targets: [{
                                ...template.backend.targets[0],
                                hostname: url.hostname,
                                port: url.port || (secured ? 443 : 80),
                                tls_config: {
                                    ...template.backend.targets[0].tls_config,
                                    enabled: secured
                                }
                            }]
                        }
                    })
                    .then(r => {
                        setLoading(false)
                        setCreatedRoute(r)
                    })
            })
    }, [])

    return <>
        <Loader
            loading={loading}
            minLoaderTime={1500}
            loadingChildren={<h3 style={{ textAlign: 'center' }} className="mt-3">Creation in process ...</h3>}>
            <button className='btn btn-save mx-auto' style={{ borderRadius: '50%', width: '42px', height: '42px' }}>
                <i className='fas fa-check' />
            </button>
            <div className="mt-3" style={{
                display: 'flex',
                justifyContent: 'center',
                alignItems: 'center',
                flexDirection: 'column'
            }}>
                <h3>Your route is now available!</h3>

                <button className='btn btn-save' onClick={() => {
                    if (["mock", "graphql"].includes(state.route.kind))
                        history.push(`/routes/${createdRoute.id}?tab=flow`, {
                            plugin: state.route.kind === "mock" ?
                                "cp:otoroshi.next.plugins.MockResponse" :
                                "cp:otoroshi.next.plugins.GraphQLBackend"
                        });
                    else
                        history.push(`/routes/${createdRoute.id}?tab=flow`, { showTryIt: true });
                }}>
                    {state.route.kind === 'mock' ? 'Start creating mocks' : state.route.kind === 'graphql' ? 'Start creating schema' : 'Try it'}
                </button>
            </div>
        </Loader>
    </>
}

export class RouteWizard extends React.Component {

    state = {
        steps: 4,
        step: 1,
        route: {
            name: "My new route",
            domain: "",
            url: "",
            kind: 'api'
        },
        error: undefined
    }

    prevStep = () => {
        if (this.state.step - 1 === 4 && ["mock", "graphql"].includes(this.state.route.kind))
            this.setState({
                step: 3
            })
        else
            this.setState({
                step: this.state.step - 1
            })
    }

    nextStep = () => {
        if (this.state.step + 1 === 4 && ["mock", "graphql"].includes(this.state.route.kind))
            this.setState({
                step: 5
            })
        else
            this.setState({
                step: this.state.step + 1
            })
    }

    onRouteFieldChange = (field, value) => {
        this.setState({
            route: {
                ...this.state.route,
                [field]: value
            }
        })
    }

    render() {
        const { steps, step, error } = this.state

        return (
            <div className='wizard-container'>
                <div style={{ flex: 1, display: 'flex', flexDirection: 'column', padding: '2.5rem' }}>
                    <label style={{ fontSize: '1.15rem' }}>
                        <i className='fas fa-times me-3' onClick={() => this.props.history.goBack()} style={{ cursor: 'pointer' }} />
                        <span>{`Create a new route (Step ${step <= steps ? step : steps} of ${steps})`}</span>
                    </label>

                    <div className="wizard-content">
                        {step === 1 && <RouteNameStep state={this.state} onChange={n => this.onRouteFieldChange('name', n)} />}
                        {step === 2 && <RouteChooser state={this.state} onChange={n => this.onRouteFieldChange('kind', n)} />}
                        {step === 3 && <FrontendStep
                            state={this.state}
                            onChange={n => this.onRouteFieldChange('domain', n)} />}
                        {step === 4 && <BackendStep
                            onError={err => this.setState({ error: err })}
                            error={error}
                            state={this.state}
                            onChange={n => this.onRouteFieldChange('url', n)} />}
                        {step === 5 && <ProcessStep state={this.state} history={this.props.history} />}
                        {step <= steps && <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }} className="mt-3">
                            {step !== 1 && <label style={{ color: '#f9b000' }} onClick={this.prevStep}>
                                Previous
                            </label>}
                            <button className='btn btn-save'
                                style={{ backgroundColor: '#f9b000', borderColor: '#f9b000', padding: '12px 48px' }}
                                disabled={error}
                                onClick={this.nextStep}>
                                {(step === steps) ? 'Create' : 'Continue'}
                            </button>
                        </div>}
                    </div>
                </div>
                <div style={{ flex: 1, borderBottom: "4px solid #f9b000" }}>
                </div>
            </div>
        )
    }
}