import React, { useEffect, useState } from 'react'
import { Route, Switch, useLocation, useParams, useRouteMatch } from 'react-router-dom'
import { nextClient } from '../../services/BackOfficeServices'
import Designer from './Designer'
import { Informations } from './Informations'
import { TryIt } from './TryIt'
import Routes from './Routes'

export default (props) => {
    const match = useRouteMatch()

    useEffect(() => {
        props.setTitle("Routes designer")
    }, [])

    return <Switch>
        <Route exact
            path={`${match.url}/:routeId`}
            component={() => {
                const p = useParams()
                const { search } = useLocation()
                const query = new URLSearchParams(search).get("tab")
                const isCreation = p.routeId === 'new'

                const [value, setValue] = useState({})

                useEffect(() => {
                    if (p.routeId === 'new') {
                        nextClient.template(nextClient.ENTITIES.ROUTES)
                            .then(setValue)
                    }
                    else
                        nextClient.fetch(nextClient.ENTITIES.ROUTES, p.routeId)
                            .then(setValue)
                }, [p.routeId])

                if (query) {
                    if (query === 'flow' && !isCreation)
                        return <div className="designer row p-0">
                            <Designer {...props} value={value} />
                        </div>

                    if (query === 'try-it')
                        return <div className="designer row p-0">
                            <TryIt route={value} />
                        </div>
                }

                return <div className="designer row p-0">
                    <Informations {...props} isCreation={isCreation} value={value} />
                </div>
            }} />
        < Route component={Routes} />
    </Switch >
}