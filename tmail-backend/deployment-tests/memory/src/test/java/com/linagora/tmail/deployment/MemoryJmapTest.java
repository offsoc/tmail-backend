/********************************************************************
 *  As a subpart of Twake Mail, this file is edited by Linagora.    *
 *                                                                  *
 *  https://twake-mail.com/                                         *
 *  https://linagora.com                                            *
 *                                                                  *
 *  This file is subject to The Affero Gnu Public License           *
 *  version 3.                                                      *
 *                                                                  *
 *  https://www.gnu.org/licenses/agpl-3.0.en.html                   *
 *                                                                  *
 *  This program is distributed in the hope that it will be         *
 *  useful, but WITHOUT ANY WARRANTY; without even the implied      *
 *  warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR         *
 *  PURPOSE. See the GNU Affero General Public License for          *
 *  more details.                                                   *
 ********************************************************************/

package com.linagora.tmail.deployment;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.testcontainers.containers.GenericContainer;

public class MemoryJmapTest implements JmapContract {
    @RegisterExtension
    static TmailMemoryExtension extension = new TmailMemoryExtension();

    @Override
    public GenericContainer<?> jmapContainer() {
        return extension.getContainer();
    }
}
