import React, { Component } from 'react';
import * as BackOfficeServices from '../services/BackOfficeServices';
import { SnowMonkeyConfig } from '../components/SnowMonkeyConfig';
import isEqual from 'lodash/isEqual';
import { Table } from '../components/inputs';
import moment from 'moment/moment';

function shallowDiffers(a, b) {
  return !isEqual(a, b);
}

function enrichConfig(config) {
  if (
    config.chaosConfig.largeRequestFaultConfig == null &&
    config.chaosConfig.largeResponseFaultConfig == null &&
    config.chaosConfig.latencyInjectionFaultConfig == null &&
    config.chaosConfig.badResponsesFaultConfig == null
  ) {
    const c = { enabled: true };
    if (!c.largeRequestFaultConfig) {
      c.largeRequestFaultConfig = {
        ratio: 0.2,
        additionalRequestSize: 0,
      };
    }
    if (!c.largeResponseFaultConfig) {
      c.largeResponseFaultConfig = {
        ratio: 0.2,
        additionalResponseSize: 0,
      };
    }
    if (!c.latencyInjectionFaultConfig) {
      c.latencyInjectionFaultConfig = {
        ratio: 0.2,
        from: 500,
        to: 1000,
      };
    }
    if (!c.badResponsesFaultConfig) {
      c.badResponsesFaultConfig = {
        ratio: 0.2,
        responses: [
          {
            status: 502,
            body: '{"error":true}',
            headers: {
              'Content-Type': 'application/json',
            },
          },
        ],
      };
    }
    config.chaosConfig = c;
    return config;
  } else {
    return config;
  }
}

export class SnowMonkeyPage extends Component {
  state = {
    originalConfig: null,
    config: null,
    started: false,
    changed: false,
    outages: [],
  };

  columns = [
    { title: 'Service Name', content: (item) => item.descriptorName },
    {
      title: 'Outage started at',
      content: (item) => moment(item.startedAt).format('YYYY-MM-DD HH:mm:ss.SSS'),
    },
    {
      title: 'Outage duration',
      content: (item) => moment.duration(item.duration, 'ms').humanize(),
    },
    {
      title: 'Outage until',
      content: (item) => {
        const parts = item.until.split('.')[0].split(':');
        return `${parts[0]}:${parts[1]}:${parts[2]} today`;
      },
    },
    {
      title: 'Action',
      notSortable: true,
      notFilterable: true,
      content: (item) => item,
      cell: (v, item, table) => {
        return (
          <button
            type="button"
            className="btn btn-success btn-sm"
            onClick={(e) =>
              (window.location = `/bo/dashboard/lines/prod/services/${item.descriptorId}`)
            }>
            <i className="fas fa-link" /> Go to service descriptor
          </button>
        );
      },
    },
  ];

  componentDidMount() {
    this.props.setTitle(`Nihonzaru, the Snow Monkey`);
    this.updateStateConfig(true);
    this.mountShortcuts();
  }

  componentWillUnmount() {
    this.unmountShortcuts();
  }

  mountShortcuts = () => {
    document.body.addEventListener('keydown', this.saveShortcut);
  };

  unmountShortcuts = () => {
    document.body.removeEventListener('keydown', this.saveShortcut);
  };

  saveShortcut = (e) => {
    if (e.keyCode === 83 && (e.ctrlKey || e.metaKey)) {
      e.preventDefault();
      if (this.state.changed) {
        this.saveChanges();
      }
    }
  };

  saveChanges = (e) => {
    // console.log('Save', this.state.config);
    BackOfficeServices.updateSnowMonkeyConfig(this.state.config).then(() => {
      this.updateStateConfig(true);
    });
  };

  updateStateConfig = (first) => {
    BackOfficeServices.fetchSnowMonkeyConfig().then((_config) => {
      const config = enrichConfig(_config);
      this.setState({ config, started: config.enabled });
      if (first) {
        this.setState({ originalConfig: config });
      }
    });
  };

  toggle = (e) => {
    if (this.state.started) {
      BackOfficeServices.stopSnowMonkey().then(() => {
        this.setState({ started: false });
        setTimeout(() => this.updateStateConfig(), 5000);
      });
    } else {
      BackOfficeServices.startSnowMonkey().then(() => {
        this.setState({ started: true });
        setTimeout(() => this.updateStateConfig(), 5000);
      });
    }
  };

  render() {
    if (!window.__user.superAdmin) {
      return null;
    }
    const moreProps = {};
    if (!this.state.changed) {
      moreProps.disabled = true;
    }
    return (
      <div>
        <div className="row">
          <div className="mb-3 btnsService">
            <div className="displayGroupBtn">
              <button
                type="button"
                className={`btn btn-${this.state.started ? 'danger' : 'success'}`}
                onClick={this.toggle}>
                <i className={`fas fa-${this.state.started ? 'stop' : 'play'}`} />
                {this.state.started ? ' Stop that damn monkey ...' : ' Unleash the monkey !'}
              </button>
              <button
                type="button"
                className={`btn btn-success`}
                {...moreProps}
                onClick={this.saveChanges}>
                <i className={`fas fa-hdd`} /> Save
              </button>
            </div>
          </div>
          <div className="col-md-12 text-center">
            <svg
              version="1.1"
              className={this.state.started ? 'monkey snowMonkeyAnim' : 'monkey'}
              title={
                this.state.started ? 'The Snow Monkey is running' : 'The Snow Monkey is not running'
              }
              xmlns="http://www.w3.org/2000/svg"
              x="0px"
              y="0px"
              viewBox="0 0 244.1 244.1">
              <g id="_x34_9a67235-e3a1-4929-8868-b30847745300">
                <g id="b11c2c3a-c434-45dc-a441-e60dd5d9d3f6">
                  <polygon
                    className="st1"
                    points="73.8,56.4 56,85.6 52.7,123.5 71,159.6 69.8,187.7 85.7,206.2 122.1,216.9 157.1,204.9 182.5,179
            			196,116.3 218.9,76.5 152.4,40.7 130.6,35.1 		"
                  />
                  <path
                    className="st0"
                    d="M213.9,111.2c6.8-2.9,13.7-5.7,20.5-8.5c-2.4-1-3.4-2-6.7-2.4c2.7-1.9,4.5-1.8,7.3-2.9
            			c-5.1,0-15.2,0.4-21.3-2.8c3.8-0.8,7.5-2,11.1-3.6c-14.4-0.6-10.8,1.5-21.1-5.3c3.6-0.8,7.9,1.9,11.8,1.4c-1.9-1.3-4-2.3-6.2-3
            			c0.8-0.2,17.5-2.1,17.5-6c0-0.5-17.6-5.5-21.3-6.2c3.4-0.3,6.7-0.8,10-1.5c-5.5-0.8-11.1-1-16.6-0.6c8-5.1,16.9-9.1,24.5-14.2
            			c-8.4,0.5-16.3,3.9-24.7,5.7c3.6-5.4,22.6-12,22.6-17c0,0.2-22.1,11.3-24.6,12.7c4.4-5.2,9.3-8.7,14.2-13.3
            			c-9.3,2.5-15.7,7.4-25.6,8.4c4.8-3.9,11-5.1,16.1-8.6c-3,0.5-5.9,1.2-8.7,2.2c0.3-0.3,12.1-10.8,11.5-11.3
            			C200,30.7,186.9,36,183,37c3.3-2.9,6.4-6,9.2-9.4c-5.7,3.1-13.8,7.8-18.9,11.7c-0.7,1.1,14.3-26,7.2-26.8
            			c-2.8-0.3-12.5,12.7-15.3,14.8c0.4-1.9,3.6-13.2,2.6-13.4c-2.3-0.3-10.6,8.1-12.8,10.8c4.4-6.6,5.4-9,11.6-14.1
            			c-4.9,1.4-9.6,3.3-14,5.7l3.5-7.3c-1,1.6-1.6,1.4-1.8-0.9c-3-0.9-14.8,26.3-5.7,2.5c-2.5,3.2-4.9,6.5-7.1,9.9
            			c1.5-5.2,3.3-10.2,5.4-15.1c-0.9,1-1.5,0.6-1.8-1.2c-2.3-0.3-7,14.7-6.6,4.8c-2.9,4.6-11.9,17.5-12.4,6.8
            			c-6.4,17.3,0.5,0.5-3.4,6.5c0.6-3.8,0.8-7.7,0.4-11.6c-1.5,3.7-2.5,7.5-3,11.4c-1-2-1.7-4.1-2.1-6.3c-0.9,4.9-1,13.1-0.8,19.3
            			c-1.3-7.2-2.5-17.3-6-24.4c-0.2,6.3,0.7,12.6,2.7,18.6c-6.5-7.9-3.7-0.1-6.6-13.8c-1.5,3-2.4,6.2-2.7,9.5
            			c-0.7-0.8-2.5-6.2-4.8-5.2c-1.7,0.7-0.9,11.2-0.9,11.5c-3.2-7.6-6.4-15.1-9.6-22.7v27.8c-4.2-6.7-4.3-15.8-7.3-23.3
            			c0.3,6.4,1.2,12.8,2.6,19c-7-2.5-7-5-11.6-10.8c4.1,25.6,0.2-0.7-0.9,4.1c1.5,1.4,2.1,3.5,1.8,5.5c-7.1-5-12.9-12.6-16.8-20.3
            			c-0.2,0.2-0.5,0.3-0.8,0.2C56,18.2,66.8,26,68.6,34C68,32.9,58.4,18.7,57.5,18.8c-4.9,0.8,4.8,12.4,6,14.3
            			c-0.9-0.8-16.7-10.4-16.3-10.5c0.7-0.2,18.4,26.9,19.7,28.8c-3.3,0.8-10.3-8-13.6-9.4c2.4,3.6,5.4,6.8,8.9,9.4
            			c-11.5-1.7-20.2-10.1-31-14.1c6.1,5,13.8,7.9,19.7,12.9c-3.7,1.2-21-5.1-21-0.5c0,0.7,32.3,14.8,34.5,16.2
            			c-12.1-2.1-23.7-8.3-35.8-9.3c5,3.2,10,11.7,14.5,17.1c-7.3,0-13.6-3.7-21.3-2.7c5.2,2.2,10.9,3.4,16.5,3.4
            			c-7.9,1.8-17.7,2.6-24.4,4.8c3.4,0.7,6.8,1.6,10.1,2.9c-4.2-0.6-8.4-0.9-12.7-1c6.2,2.2,12.6,3.7,19.1,4.6
            			c-5.1-0.3-9,0.8-12.3,1.1l24.9,10.6c-5,4.1-24.7,1.8-29.2,2.7c1.2,0.7,0.4,1-2.6,0.9c5.7,1.5,6.4,2.7,11.4,5
            			c-5.2-0.3-11.1-0.2-16.1,1.4c7.8,2.1,15.4,1.7,22.8,3.6c-2,1.7-23.2,9.3-23.2,11.9c0,3,27.4,2.1,28.9,4.4c-6,6.3-13.8,9.7-24.6,14
            			c3.6,0.3,7.3,0.3,10.9-0.2c-5,6-11.7,13.6-15.5,12.4c-0.7,2.9,0.1,2.6-1.9,5.2c4.3-0.2,0.1-1,3.3,1.3c4.9-7.9,17-13.2,26.1-18.6
            			c-3.2,6.8-5,8.3-11.6,12.7c12.3-1.8-2,0.9,4.1,3c2.1,0.7,15.5-6.7,19.1-7.5c-9.5,7.9-13,17.3-23.2,25.1c11.4-3.4,2.4-1.7,2.3,4.4
            			c4-3,8.7-8.6,13.1-10.7c-1.9,2-18.7,39.1-16.8,39.9c2.4,0.9,19.6-20.2,23.1-22.8c-2.9,3.2-5.2,7-8,10.3c5.4-3.5,11.5-6.2,16.9-9.8
            			c-4.2,4.4-7.9,9.2-11,14.5c6.8-5,12.6-12.3,18-18.6c0,24.1-0.8,26.1-16.8,41.8c3.5-1.5,11.6-5,14.7-8.2c2-15.3-2.9,9.1-0.9,9.9
            			s15-6.8,17.3-7.9c-3.2,3.4-6.1,7.1-8.7,11c10.5-7.1,17.9-20.8,18.9-0.8c16.9-28.6,26.2,10.4,35.5,9.1c0.3-0.1,8.9-11.6,11.9-13.1
            			c7.8-3.6,9.2-0.4,20.6-4c9.4-3,9.6,13,12.6-6.3c11.1,8.1,16.2,4.4,18.4-8.8c0.4,0.4,9.7,8.8,10.1,8.7c5.5-2.2-2.3-18.6,4.7-8
            			c0.2-3.6-1.4-6.9-1.3-10.4c4.7,3.1,4.9,8.4,8.6,12.9c-0.3-3.4-1.1-6.7-2.5-9.9c2.4,1,4,4.1,5.7,5.9c-0.1-2,1.2-8.4-0.6-14
            			c5.4,2.5,8,7.4,13.3,11.9c-2.5-4.8-5.4-9.3-8.7-13.4c4.7-5.6,10.2-0.4,12.7,1.8c-5.4-8.7-9.1-14.4-9.4-24.8
            			c5.8,0.7,9.6-1,15.1,0.8c-2.9-6.4-8.6-9.8-12.3-16c2.8,1.7,5.7,3.2,8.8,4.4c-7.4-7.6-15.7-11.6-21.9-20
            			c5.3-1.7,13.2,11.2,16.9,9.9c4.2-1.5-11-10.9-11.5-11.9c4-2.2,14.2,4.3,18.6,6.5c-6.8-7.7-11.4-12.5-19.1-18.4
            			c3.3,2.2,7.1,3.6,11,4.1c-3.3-3.6-12.3-4.4-14.4-7.4c8.9-1.9,17.5-4.7,25.9-8.1c-9.3,1.2-18.4,3.2-27.3,6
            			C204.4,114.3,213.4,111.5,213.9,111.2z M225,101.3c1.7,0.4,3.2-0.6,4.6,1.1C230.3,102.5,222.1,102.2,225,101.3L225,101.3z
            			 M222.3,100.1c3,3.1-4,1.3-6.3,1.3L222.3,100.1z M40.2,84.9c-3.9-0.7-8.2-1.4-12.2-2.1C31.5,82,38.1,84.4,40.2,84.9z M32.6,171.4
            			c-0.3,0.4-5.2,5.9-6.5,6.3c0.1-0.1,0-0.1-0.1,0C25.2,179.1,35.8,167.2,32.6,171.4z M154.1,18.4l4.8-2.6c-5.2,5.6-8.4,15.5-15.1,19
            			C145.3,29.2,148.9,21,154.1,18.4L154.1,18.4z M149,17.1c1.4-2.5,2.7-5.9,4.8-8c0.4,1.3-2.6,5.2-3.3,6.5l2.4-2.5
            			C147.3,20.1,149.4,17.6,149,17.1z M127.3,28.7c0.8-3.3,2.6-7.1,5.1-9.4C132.5,23.4,125.5,36.4,127.3,28.7z M176,113.7
            			c-1.8,2.6-4,4.9-6.6,6.7c10.3,3.5,8,22.1-4,19.2l5.2-7.5c-3.2,1.7-14.8,6-16.2,8.5c-4.5,8.4,3.3-1.7,0.2,10.5
            			c-2.7,10.6,3.4,21-9.7,27.2c19.1,10.6-37.4,31.8-53.4,12.4c-11.3-13.7,1.7-7.5,2-16.3c0.3-12.9-9.7-18.1,1.4-30.8
            			c-22.2,26.1-47.2-45.6-14-66.3h-6.7c3.4-3.5,6.9-8,10.8-10.9c-3.1,2.7-5.8,6.2-8.6,9.2c3.9-1.2,8.2-1.8,12-3.3l-3.7,3.3
            			c4.1-2.1,8.9-2.8,13.2-4.6c-2.6,3.5-4.1,8-6.9,11.3l18.3-12.2v7.2c3.7-2.7,9.4-2.7,13.2-5.3l2.3,7.1c1-3.9,3.2-5,3.3-10.7
            			c1.7,6.5,3.2,8,9.6,7.8c12.7-0.4,1.2-7.7,19.4,0.1C168.1,81.1,185,100.2,176,113.7L176,113.7z M200.7,92c-1.3,0.7-4.4-0.6-2-2
            			C200,89.2,203.1,90.6,200.7,92z"
                  />
                  <path
                    className="st0"
                    d="M93.2,91.2c0,0,17.2,2.4,19.4,3.2s0,7.7,0,7.7s0.3-1.8-5.3-4S90.7,97.7,87.3,99l-8.5,3.2l-2.1-0.9l-7-3.1
            			l11.6,6.8H64.1l16.9,2.8l4.7-2.5c0,0,2.8,5.9,7.2,6.9s11.3,1.6,15.7,0c2.6-0.9,5.1-1.9,7.5-3.1c0,0,0,5.3-1.6,7.8
            			s-13.8,13.2-13.8,13.2l-10.6-11.3c0,0,7.2,1.6,11.9,0.3c2.8-0.7,5.5-1.7,8.2-2.8c0,0-12,0.6-16.7,0s-8.8-5.6-8.8-5.6l-1.9,1.9
            			l2.8,10l-5.9-2.1l10.3,15.3c0,0-8.8,0.6-12.5-5.6s-6.2-16-7.8-16.6s-1.9,8.4-1.9,8.4l9.7,15.4l8.2,4.1l6.9,2.4c0,0,0.9,1-2.5,6.5
            			s-7,9.6-4.4,15.5s-0.9,2.8,2.6-1.3s5.6-13.2,9.7-14.7s10-3.9,10-3.9s4.1-9.6,8.2-10.6s8.5-0.3,11.4,0s6.1,5.2,6.1,6.5
            			c0.4,2.1,0.9,4.2,1.6,6.3l10.2,6.6c0,0-12.4-19.1-11.8-21s11.8,1.6,11.8,1.6s-12.9-11.3-14.8-14.9s-4.8-8.9-3.1-10.5
            			c1.7-1.6,2.4-0.9,6.1,0.9s13.5,3.8,15.2,3.4s12.4-3.8,12.4-3.8s-12.5,1.6-15.8,1.2s-14.8-4.4-14.8-4.4s5.6-5.3,5.6-7.5
            			s1.2-9.7,6-8.8s15,3.7,19.1,6.2s12.5,3.7,12.9,1.6s-6-9.4-10-9.4s-14.4-4.7-18.6-4.7s-12.1,5.3-17.9,4.7s-14.6-3.1-19-2.8
            			s-14.1-4.4-19.7-1.9s-20.7,8.4-22.3,9.5s-8.8,8.7-6.9,9s11.3-1.3,11.3-1.3s-7.2-3.9-2.8-5.6S93.2,91.2,93.2,91.2z"
                  />
                  <path
                    className="st0"
                    d="M110.9,147.6c0,0,0.6,1.9,3.6,2.2c1.6,0.1,3.2,0.6,4.7,1.3c0,0,2,3.3,2,4.9s0.8,15.7,0.8,15.7
            			s-1.9-14-2.2-15.2s-3.7-2.2-5.8-3.3S110.9,147.6,110.9,147.6z"
                  />
                  <path
                    className="st0"
                    d="M134,147.6c0,0-0.6,1.9-3.6,2.2c-1.6,0.1-3.2,0.6-4.7,1.3c0,0-2,3.3-2,4.9s-0.8,15.7-0.8,15.7
            			s1.9-14,2.2-15.2s3.7-2.2,5.8-3.3S134,147.6,134,147.6z"
                  />
                  <path
                    className="st0"
                    d="M113.6,97.5c0,0-3.4-3.8-7.1-5.2s-17.7,1.4-19.4,2.6s-5.6,4.9-4.2,6.7s-2.5,5.2-2.5,5.2s-7.5,0.8-8.2,3.3
            			s-1.6,4.4,0.9,10.5s-2.3,6.2-2.3,6.2s-3.8-3.6-7.2-12.2S57,104.5,62,100s13.8-8.8,18.6-9.7s18.8-0.3,19.9-0.6s11.1,1.2,13,2.3
            			s3.9,3.9,2.9,4.9C115.6,97.4,114.6,97.6,113.6,97.5z"
                  />
                  <path
                    className="st0"
                    d="M72.2,93.4c0,0-4.3,15.8,1.4,6.5s9.8-11.6,9.8-11.6s13.9-4.2,14.6-1.4s-4.3-10.5-4.3-10.5l2.8,2.5l6.9-9.6
            			l4.3,9.4l8.3-10.3c0,0,36.8-3,37.1-6.8s-40.2-19.2-40.2-19.2L69.8,54.4L47.2,93.4l0.8,29.4l24.2,31.7l10.4,12.7
            			c0,0,12.4-14.1,13.6-18.6s-2-7.2-8.2-9.2s-17-9.8-19.3-22c-1.5-7,0.2-14.4,4.7-20L72.2,93.4z"
                  />
                  <path
                    className="st1"
                    d="M11,149.6c14.4-11.7,28.1-36.6,48.4-35.1l-3.4,2.7l3.4-0.7l-2.2,3.1l4.3-0.4c-1.7,2.1-2.8,4.5-3.2,7.2
            			c1.9,1.2,4.4,0.8,5.8-1l-4.7,9.8l4.7-3.2l-1.7,7.9c0.9-1.6,2-2.2,3.5-1.8c-3.2,6.1,4.2,8.4,3.8,9.6c-0.3-2.4,0.6-4.7,2.5-6.2
            			c-0.3,2.9-1.1,5.7-2.5,8.2l4.9-2.9l-2.4,5.3l3.8-2.3l-1.2,4.1l3.3-4.1v5c3.2-1.5,6.7-3.8,9.9-5l-6.1,6.8c3.2-2.5,7-4.4,10.3-6.8
            			c-3.7,4.3-5.9,9.6-6.4,15.3c-0.2,4.3,3.5,7.9,3.3,11.9c-0.2,4.3-8.6,9.1-8.6,10.4c-0.2,14.8,36.9,21.5,10.1,33.5
            			c1.6-3.9,2.2-8.3,3.9-12.1c-2.7,2.7-5.3,5.4-7.8,8.3c1.2-1.6-2.8-9.3,1.2-11c-6.8,3-13.4,4.6-19.5,9.9c3-5.8,4.3-11.1,8.2-16.4
            			c-11.8,4-18.6,14-20.8,25.8c0.2-2.2-1.6-17.6,0.2-18.1c-2.1,0.6-9.1,10.7-10.4,12.8c3-6.6,6.6-19.4,12.3-24.2
            			c-3.1,2.6-6.4,5.4-9.2,8.3c3.8-7.3,6.8-14.9,9.2-22.8c-5.9,3.2-12.8,5.5-18.3,9.4c5.5-3.7,14.1-8.5,17.6-14.3
            			c1.1-1.8-21.6,14.4-26.1,18.6c2.2-3.6,3.9-7.6,6.1-11.1l-16.2,20.5c4.6-21,18.5-37.7,36.9-48.4c-18.6,1.4-24.6,20.5-45.5,21.4
            			c17.4-7.3,25.6-22.5,41.2-30.5c-7.3,1.8-13.9,6.1-18.5,12.1c2.9-9.4,11.2-15.3,20.5-17.3c-14.6-0.4-21.3,12.6-34.7,15
            			c11.1-8,19.1-20.5,31.8-25.7c-9.2,2.8-17.6,7.8-24.5,14.6c7.8-7.3,16.6-13.6,26.1-18.5C38.2,127.1,25.3,142.5,11,149.6z"
                  />
                  <path
                    className="st1"
                    d="M102.6,201c5.2,5.5,8.2,12.7,8.3,20.3l2-10.1c3.5,4.6,8.5,9,11.3,14.1c-4.2-4.7-7.2-11-10.8-16.2
            			c4.3,3.8,6,3.2,10.8,10.6c1.9-3.7,5.1-7,6.6-10.8c-3.4,8.3-3.2,17.1,2,24.2l3.9-7.8l1.4,7.8l7.5-9.2c0.3,10.1,2.5,8.4,6.4,16
            			c-0.9-1.8-5.6-19.7-4-19.4c6.5,1.2,15,11.6,18.6,16.1c-2.6-5-4.5-10.4-7.3-15.2c1.3,2.1,8.6,13.8,11.1,13.8
            			c0.6,0-2.9-12.9-3.3-13.8c3.3,5.9,7.2,9.8,11.3,14.8c-2.7-5.8-4.4-12-7.3-17.6c2.5,4.6,6.2,8.5,8.9,12.9c-0.4-1.3-0.4-18.1,0-17.6
            			c4.9,5.5,9.6,11.3,14.3,16.9c-3.7-7.5-9.4-16.5-11-24.8c0.9,4.4,10,13.4,12.7,18.2c-1.8-4.8-2.5-10-4.2-14.8
            			c3.4,5.5,7.9,10.4,11.3,16c-3.4-12.3-7-24.3-8-37.1l12.3,23.5c-2.2-6.1-3.1-12.6-5-18.8l18.8,18.6l-13.8-20.9l4.6,2.1
            			c-8.8-11.6-17-27.8-31-33c18,1.5,28.3,20.3,36.7,34.4c-2.9-7.7-5.4-15.6-7.6-23.5c3.3,6.2,7.7,11.9,11.1,18.1
            			c-1.9-18.3-12.8-31.2-26.6-42.7c12.6,6.3,17.7,16.9,27.7,25.6c-5.3-9.9-9.3-20.1-18.3-27.3c9.7,8,19.6,15.4,27.8,25.1L215.5,150
            			c7.6,4.4,15.9,7.7,24.6,9.6c-14.4-17.5-35-28.5-53.7-40.8c18.7-2.8,33.2,10.8,47.6,20.6c-13.5-13.2-25-21.9-41.9-30.3
            			c14.4,0,28.6,1.7,41.9-4.7c-11.9,0-32.1,4.2-40.5-4.5c11.9,0.9,23.9,0.9,35.9,0L200.6,95l26.8,1.6l-41.9-9.8
            			c11.1-7.2,22.2-5.2,35-5.1c-12.4-1.1-24.8-1.6-36.9-4.6l33.9-3.5c-14.4,0-30.4,2.6-43.5-2.6c13.1-1.8,26.2-2.6,38.3-8.5l-33.2,4.9
            			c10.9-4,22.8-5.4,28.1-16.9l-18.6,10.8c3.9-3.6,7-8.1,10.8-11.8c-13.4,7-29.9,17.1-45.2,11.8c3.7-14.6,21.8-10.6,34.8-14.3
            			c-4.1-0.7-8.1-2.4-12.2-3l11.1-1.7l-4.5-2.1l20.2-10.3l-21.2,8l21.4-16.2L176,38.8l2.3-7.2c-5.5,1.1-10.8,3-15.7,5.6l5.4,4.2
            			c-9.6,2.5-16.1,4.2-17.4,9.6l-8.2-4c9.8-6.9,17.3-17.1,22.6-27.8l-16,17c2.1-4.3,3.3-9.1,5.4-13.4c-4.1,5.7-9.7,10.4-14.1,16
            			c1-6.4,0.9-13.2,2.4-19.6c-0.1,4.5-2.9,10-4,14.4c-7-2.7-12.9-7.8-16.5-14.4l2.4,10.4c-4.2-2.2-9.5-3-13.6-5.6
            			c4.2,3.5,7.1,8.5,11.1,12.2c-12-2.7-24.1-3.4-36.1,0c4.1,1.5,7.8,4.2,12,5.6c-4-0.1-8,0.9-12,0.7c4.5,1.1,8.7,4,13.2,5.4
            			c-6,2.1-11.7,5.9-17.9,7.5c6.1-0.3,12.2,1.1,18.3,0.7c-6.2,2.1-12.1,5.1-18.3,7c6.7-1.2,13.5-2.1,20.2-3.5
            			c-10.7,2.5-19.7,2.2-25,10.5c4.9-2,10.9-2.8,15.6-5.3l-9.3,6c4.7-2.4,9.7-4.2,14.5-6.5c-3.2,2.4-6,5.3-9.1,7.8
            			c3.9-2.3,8.2-3.9,12.2-6.1c-3.3,3.6-6.1,7.7-9.6,11.1c5.7-6.2,12.2-12.4,20.2-14.3l-4.1,9.2c3-2.9,6.7-5.2,9.6-8.2l-5.6,9.2
            			c3.7-4.1,8.1-7.5,11.9-11.5c-1.9,3.8-3.9,7.7-6.1,11.3c2.8-4.6,3.5-11.6,5.3-16.7c-8.9-0.4-15.6-5-18-13.9
            			c3.9,3.6,8.8,6.2,12.7,9.9c-2.3-3.2-3.7-7.2-5.8-10.6c4.2,2.7,9.7,3.2,14,5.6l-2.1-8.5l5.6,5.4l5.6-7.7l-1.3,10.6l5.6-2.8
            			c-4.6,2.8-5,6.2-10.8,8.5c0.7,0.6,7.6,6.6,8,6.6c-2.7,0.5-5.4,1.3-8,2.3c8.5,6.6,16.1,0.9,23.3-5.4l-5.4,7
            			c10.9,8.5,24.6,14.2,31.1,27c5.4,10.6,10.2,51.4-2.4,51.9l1.2,5.7l-3.9-4.7l1.1,8.3l-4.5-5.3l0.7,6.2l-6.1-4.5v3.6
            			c-3.4-3.4-8.2-6.2-11.6-9.8c4.1,7.5,7.6,15.2,10.6,23.2l-4.5-5c1.6,10,0.8,16-5.9,21.5c8.9-5.8,15.9-3.9,22.9,4.5
            			c-0.9-1.1-3.9,3.2-5.2,4c-4.2-8.4-11.7-9.6-19.9-6.9c9.4,7.4,22.3,9.1,27.2,20.5c-8.1-3.6-13-9.9-21.9-12.9
            			c5.4,4.8,9.4,10.3,15.3,14.6c-7.4-0.5-13.4-4.3-18-10.2c6.1,6.4,12,12.9,12.4,21.9c-8.6-2.8-12.2-10.5-15.1-19l-2.8,5.4
            			c-4.9-1.1-9.9-0.2-16.6,0.2c-4.6,0.3-4.1,2.5-12.7,1.4C108.8,203.5,111.1,204.7,102.6,201z"
                  />
                  <path
                    className="st0"
                    d="M132.7,101.8c0,0,6.6-3.8,9.6-3.6s9.6-0.2,12,0.8s5.2,2.4,6.6,2.9s11,1.4,11,1.4l-7.3,1.4l4.7,2.3l-6.8,1.1
            			l-4.4-1.3c0,0-1.8,0.7-5.2,2.3s-8.8,0.7-11.6,0c-2.9-0.5-5.8-0.8-8.7-0.9c0,0,3.1-2.8,3.3-3.5S132.7,101.8,132.7,101.8z"
                  />
                  <path
                    className="st0"
                    d="M130.6,179l-8.5,1.1c0,0-1.3-0.2-3.2-0.4c0.5-2.1,0.9-4.3,1-6.6c-0.1-2.6-0.1-11-2.2-13.5s-6-4.9-7.5-6
            			s-2.2-4-2.2-4s-8,3.1-9.9,4.7s-6.4,8.2-7.4,10.3s-1.9,7.7-1.5,8.7c0.2,2,0.2,3.9,0,5.9c-1.8,0.5-6.6,2.1-7.4,5
            			c-1.1,3.6,3.4,13.1,10.2,17.3c4.5,2.7,9.5,4.7,14.6,6c0,0,10.1,0.2,10.1-1.4s5.5-7.2,5.3-11.4s-2.2-10.5-2.2-10.5
            			s-7.1,3.4-11.1,2.7c-3.8-0.7-15.9-2.2-14-3.4c4.8,0.4,13.1,1,19.5,0.3c6.5-0.6,13-0.6,19.4,0c3.4,0.2,15.3-1.3,15.3-1.3L130.6,179
            			z"
                  />
                </g>
              </g>
            </svg>
          </div>
        </div>
        <div className="row">
          <SnowMonkeyConfig
            config={this.state.config}
            onChange={(config) => {
              this.setState({
                config,
                changed: shallowDiffers(this.state.originalConfig, config),
              });
            }}
          />
        </div>
        <hr />
        <h3>Current outages</h3>
        <Table
          parentProps={this.props}
          selfUrl="snowmonkey"
          defaultTitle="Current outages"
          defaultValue={() => ({})}
          itemName="outage"
          columns={this.columns}
          fetchItems={BackOfficeServices.fetchSnowMonkeyOutages}
          showActions={false}
          showLink={false}
          extractKey={(item) => item.descriptorId}
        />
      </div>
    );
  }
}
